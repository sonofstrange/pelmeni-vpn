from __future__ import annotations

import ctypes
import hashlib
import ipaddress
import json
import os
import re
import socket
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from .storage import APP_DIR


TUN_NAME = "PelmeniVPN"
TUN_ADDRESS = "10.33.0.2"
TUN_GATEWAY = "10.33.0.1"
TUN_MASK = "255.255.255.0"
DNS_SERVERS = ("1.1.1.1", "1.0.0.1")
FULL_TUNNEL_ROUTES = (
    "0.0.0.0/1",
    "128.0.0.0/1",
)
# The SSH endpoint used by Pelmeni does not forward IPv6 direct-tcpip channels.
# Keep the Windows tunnel IPv4-only so Happy Eyeballs can fall back immediately.
FULL_TUNNEL_IPV6_ROUTES: tuple[str, ...] = ()
TUN2SOCKS_SHA256 = "A1F8AC84852ED9A9C7A50949CD0290B6F1594E118BF053416B9B55B7CD7AE414"
WINTUN_SHA256 = "E5DA8447DC2C320EDC0FC52FA01885C103DE8C118481F683643CACC3220DAFCE"
STATE_PATH = APP_DIR / "tun-state.json"


def _runtime_dir() -> Path:
    if hasattr(sys, "_MEIPASS"):
        meipass_runtime = Path(sys._MEIPASS) / "runtime"
        if (meipass_runtime / "tun2socks.exe").is_file():
            return meipass_runtime
    exe_runtime = Path(sys.executable).parent / "runtime"
    if (exe_runtime / "tun2socks.exe").is_file():
        return exe_runtime
    base = Path(__file__).resolve().parents[1]
    return base / "runtime"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def _network_parts(prefix: str) -> tuple[str, str]:
    network = ipaddress.IPv4Network(prefix, strict=False)
    return str(network.network_address), str(network.netmask)


def _resolve_entries(entries: list[str]) -> tuple[list[str], list[str]]:
    ipv4: set[str] = set()
    ipv6: set[str] = set()
    for raw in entries:
        value = str(raw).strip()
        if not value:
            continue
        try:
            network = ipaddress.ip_network(value, strict=False)
            (ipv4 if network.version == 4 else ipv6).add(str(network))
            continue
        except ValueError:
            pass
        host = value.split("://", 1)[-1].split("/", 1)[0].split(":", 1)[0].strip("[]")
        try:
            answers = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
        except socket.gaierror as error:
            raise RuntimeError(f"Не удалось определить адрес {value}: {error}") from error
        for family, _, _, _, address in answers:
            if family == socket.AF_INET:
                ipv4.add(f"{address[0]}/32")
            elif family == socket.AF_INET6:
                ipv6.add(f"{address[0]}/128")
    return sorted(ipv4), sorted(ipv6)


class WindowsTunManager:
    def __init__(self) -> None:
        self.process: subprocess.Popen[bytes] | None = None
        self._log: Any | None = None
        self._routes: list[dict[str, Any]] = []

    @staticmethod
    def is_admin() -> bool:
        return os.name == "nt" and bool(ctypes.windll.shell32.IsUserAnAdmin())

    def is_active(self) -> bool:
        return self.process is not None and self.process.poll() is None

    def _run(self, args: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        result = subprocess.run(
            args,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            creationflags=flags,
        )
        if check and result.returncode:
            message = (result.stderr or result.stdout).strip()
            raise RuntimeError(message or f"Команда завершилась с кодом {result.returncode}")
        return result

    def _powershell(self, script: str, *, check: bool = True) -> str:
        result = self._run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
            check=check,
        )
        return result.stdout.strip()

    def _default_route(self) -> tuple[int, str]:
        value = self._powershell(
            "$r=Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' "
            "-ErrorAction Stop | Where-Object {$_.NextHop -ne '0.0.0.0'} | "
            "Sort-Object RouteMetric,InterfaceMetric | Select-Object -First 1; "
            "if($null -eq $r){throw 'Нет активного шлюза IPv4'}; "
            "Write-Output ($r.InterfaceIndex.ToString()+'|'+$r.NextHop)"
        )
        index, gateway = value.split("|", 1)
        return int(index), gateway.strip()

    def _interface_index(self) -> int:
        output = self._run(
            ["netsh.exe", "interface", "ipv4", "show", "interfaces"],
            check=False,
        ).stdout
        for line in output.splitlines():
            if TUN_NAME.lower() in line.lower():
                match = re.match(r"\s*(\d+)\s+", line)
                if match:
                    return int(match.group(1))
        raise RuntimeError("Адаптер Wintun ещё не создан.")
    def _verify_runtime(self) -> tuple[Path, Path]:
        runtime = _runtime_dir()
        executable = runtime / "tun2socks.exe"
        driver = runtime / "wintun.dll"
        if not executable.is_file() or not driver.is_file():
            raise RuntimeError("Компоненты VPN отсутствуют. Переустанови приложение.")
        if _sha256(executable) != TUN2SOCKS_SHA256 or _sha256(driver) != WINTUN_SHA256:
            raise RuntimeError("Проверка целостности компонентов VPN не пройдена.")
        return executable, driver

    def _save_state(self) -> None:
        APP_DIR.mkdir(parents=True, exist_ok=True)
        payload = {
            "pid": self.process.pid if self.process else 0,
            "routes": self._routes,
            "tun_name": TUN_NAME,
        }
        temporary = STATE_PATH.with_suffix(".tmp")
        temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        temporary.replace(STATE_PATH)

    def _apply_routes(self, routes: list[dict[str, Any]]) -> None:
        self._routes = list(routes)
        self._save_state()
        for route in routes:
            prefix = str(route["prefix"])
            index = int(route["interface_index"])
            if route["family"] == 4:
                network, mask = _network_parts(prefix)
                gateway = str(route["gateway"])
                command = [
                    "route.exe", "ADD", network, "MASK", mask, gateway,
                    "METRIC", "1", "IF", str(index),
                ]
                result = self._run(command, check=False)
                if result.returncode:
                    self._run([
                        "route.exe", "CHANGE", network, "MASK", mask, gateway,
                        "METRIC", "1", "IF", str(index),
                    ])
            else:
                result = self._run([
                    "netsh.exe", "interface", "ipv6", "add", "route",
                    f"prefix={prefix}", f"interface={index}", "nexthop=::",
                    "metric=1", "store=active",
                ], check=False)
                if result.returncode:
                    self._run([
                        "netsh.exe", "interface", "ipv6", "set", "route",
                        f"prefix={prefix}", f"interface={index}", "nexthop=::",
                        "metric=1", "store=active",
                    ])

    def _delete_routes_batch(self, routes: list[dict[str, Any]]) -> None:
        for route in reversed(routes):
            prefix = str(route.get("prefix", ""))
            index = int(route.get("interface_index", 0))
            if route.get("family") == 4:
                network, mask = _network_parts(prefix)
                self._run([
                    "route.exe", "DELETE", network, "MASK", mask,
                    str(route.get("gateway", "")), "IF", str(index),
                ], check=False)
            else:
                self._run([
                    "netsh.exe", "interface", "ipv6", "delete", "route",
                    f"prefix={prefix}", f"interface={index}", "nexthop=::",
                    "store=active",
                ], check=False)

    def _configure_interface(self) -> int:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if self.process is not None and self.process.poll() is not None:
                raise RuntimeError("tun2socks завершился до создания VPN-адаптера.")
            try:
                index = self._interface_index()
                break
            except Exception:
                time.sleep(0.1)
        else:
            raise RuntimeError("Windows не создала адаптер Wintun за 10 секунд.")
        self._run([
            "netsh.exe", "interface", "ipv4", "set", "address",
            f"name={TUN_NAME}", "source=static", f"address={TUN_ADDRESS}",
            f"mask={TUN_MASK}", f"gateway={TUN_GATEWAY}", "store=active",
        ])
        self._run([
            "netsh.exe", "interface", "ipv4", "set", "interface",
            f"interface={TUN_NAME}", "metric=1",
        ])
        self._run([
            "netsh.exe", "interface", "ipv4", "set", "dnsservers",
            f"name={TUN_NAME}", "source=static", f"address={DNS_SERVERS[0]}",
            "register=none", "validate=no",
        ])
        for dns_index, address in enumerate(DNS_SERVERS[1:], start=2):
            self._run([
                "netsh.exe", "interface", "ipv4", "add", "dnsservers",
                f"name={TUN_NAME}", f"address={address}",
                f"index={dns_index}", "validate=no",
            ])
        return index

    def start(
        self,
        socks_port: int,
        server_host: str,
        mtu: int = 1500,
        split_mode: str = "",
        split_entries: list[str] | None = None,
    ) -> None:
        self.stop()
        if not self.is_admin():
            raise RuntimeError("Для VPN нужен запуск Пельмени VPN от имени администратора.")
        executable, _ = self._verify_runtime()
        default_index, default_gateway = self._default_route()
        server_answers = socket.getaddrinfo(server_host, None, socket.AF_INET, socket.SOCK_STREAM)
        server_ips = sorted({answer[4][0] for answer in server_answers})
        if not server_ips:
            raise RuntimeError("Не найден IPv4-адрес SSH-сервера.")
        safe_mtu = min(1500, max(1280, int(mtu)))
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        APP_DIR.mkdir(parents=True, exist_ok=True)
        self._log = (APP_DIR / "tun2socks.log").open("ab")
        self.process = subprocess.Popen(
            [
                str(executable),
                "-device", f"tun://{TUN_NAME}",
                "-proxy", f"socks5://127.0.0.1:{int(socks_port)}",
                "-mtu", str(safe_mtu),
                "-loglevel", "warn",
            ],
            cwd=str(executable.parent),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=self._log,
            creationflags=flags,
        )
        self._routes = []
        self._save_state()
        try:
            tun_index = self._configure_interface()
            selected_v4, _selected_v6 = _resolve_entries(list(split_entries or []))
            if split_mode == "only":
                tun_v4 = selected_v4
            else:
                tun_v4 = list(FULL_TUNNEL_ROUTES)
            tun_v6: list[str] = []
            routes: list[dict[str, Any]] = []
            for address in server_ips:
                routes.append({
                    "family": 4,
                    "prefix": str(ipaddress.IPv4Network(f"{address}/32", strict=False)),
                    "gateway": default_gateway,
                    "interface_index": default_index,
                    "kind": "server",
                })
            for prefix in tun_v4:
                routes.append({
                    "family": 4,
                    "prefix": str(ipaddress.IPv4Network(prefix, strict=False)),
                    "gateway": TUN_GATEWAY,
                    "interface_index": tun_index,
                    "kind": "tun",
                })
            for prefix in tun_v6:
                routes.append({
                    "family": 6,
                    "prefix": str(ipaddress.IPv6Network(prefix, strict=False)),
                    "interface_index": tun_index,
                    "kind": "tun",
                })
            if split_mode == "bypass":
                for prefix in selected_v4:
                    routes.append({
                        "family": 4,
                        "prefix": str(ipaddress.IPv4Network(prefix, strict=False)),
                        "gateway": default_gateway,
                        "interface_index": default_index,
                        "kind": "bypass",
                    })
            unique: dict[tuple[Any, ...], dict[str, Any]] = {}
            for route in routes:
                key = (route["family"], route["prefix"], route["interface_index"], route.get("gateway", ""))
                unique[key] = route
            self._apply_routes(list(unique.values()))
        except Exception:
            self.stop()
            raise
    def stop(self) -> None:
        routes, self._routes = self._routes, []
        try:
            self._delete_routes_batch(routes)
        except Exception:
            pass
        process, self.process = self.process, None
        if process is not None and process.poll() is None:
            try:
                process.terminate()
                process.wait(timeout=3)
            except Exception:
                try:
                    process.kill()
                    process.wait(timeout=2)
                except Exception:
                    pass
        log, self._log = self._log, None
        if log is not None:
            try:
                log.close()
            except Exception:
                pass
        try:
            STATE_PATH.unlink(missing_ok=True)
        except OSError:
            pass

    def restore_stale(self) -> None:
        if not STATE_PATH.exists() or not self.is_admin():
            return
        try:
            state = json.loads(STATE_PATH.read_text(encoding="utf-8"))
            self._delete_routes_batch(list(state.get("routes") or []))
            pid = int(state.get("pid", 0))
            if pid > 0:
                name = self._powershell(
                    f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}' -ErrorAction SilentlyContinue; if($p){{$p.Name}}",
                    check=False,
                )
                if name.strip().lower() == "tun2socks.exe":
                    self._run(["taskkill.exe", "/PID", str(pid), "/T", "/F"], check=False)
        finally:
            try:
                STATE_PATH.unlink(missing_ok=True)
            except OSError:
                pass
