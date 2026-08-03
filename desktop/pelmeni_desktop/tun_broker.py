from __future__ import annotations

import ctypes
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from .storage import APP_DIR
from .windows_tun import WindowsTunManager as DirectWindowsTunManager


_REQUEST_PATTERN = re.compile(r"vpn-helper-([0-9a-f]{32})-request\.json")


def _control_paths(request_path: Path) -> tuple[Path, Path]:
    prefix = request_path.name.removesuffix("-request.json")
    return request_path.with_name(prefix + "-status.json"), request_path.with_name(prefix + "-stop")


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    temporary.replace(path)


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def _socks_listener_ready(port: int, timeout: float = 0.4) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", int(port)), timeout=timeout):
            return True
    except OSError:
        return False

def _process_alive(pid: int) -> bool:
    if os.name != "nt" or pid <= 0:
        return False
    process_query_limited_information = 0x1000
    still_active = 259
    handle = ctypes.windll.kernel32.OpenProcess(process_query_limited_information, False, pid)
    if not handle:
        return False
    try:
        exit_code = ctypes.c_ulong()
        return bool(ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code))) and exit_code.value == still_active
    finally:
        ctypes.windll.kernel32.CloseHandle(handle)


def _launch_elevated(request_path: Path) -> None:
    if os.name != "nt":
        raise RuntimeError("VPN helper поддерживается только в Windows")
    if getattr(sys, "frozen", False):
        sibling_helper = Path(sys.executable).with_name("PelmeniVPN-TunHelper.exe")
        if sibling_helper.is_file():
            executable = str(sibling_helper)
            arguments = ["--request", str(request_path)]
        else:
            executable = sys.executable
            arguments = ["--tun-helper", str(request_path)]
    else:
        executable = sys.executable
        arguments = [str(Path(sys.argv[0]).resolve()), "--tun-helper", str(request_path)]
    parameters = subprocess.list2cmdline(arguments)
    shell_execute = ctypes.windll.shell32.ShellExecuteW
    shell_execute.restype = ctypes.c_void_p
    result = shell_execute(None, "runas", executable, parameters, str(Path(executable).parent), 0)
    code = int(result or 0)
    if code <= 32:
        if code in (5, 1223):
            raise RuntimeError("Запрос прав администратора для VPN отменён")
        raise RuntimeError(f"Не удалось запустить системный VPN helper: код {code}")


class ElevatedTunManager:
    """Runs only Wintun and route changes elevated; the UI stays unelevated."""

    def __init__(self) -> None:
        self._direct = DirectWindowsTunManager()
        self._request_path: Path | None = None
        self._status_path: Path | None = None
        self._stop_path: Path | None = None
        self._helper_pid = 0

    @staticmethod
    def is_admin() -> bool:
        return DirectWindowsTunManager.is_admin()

    def is_active(self) -> bool:
        if self._direct.is_active():
            return True
        if self._status_path is None:
            return False
        status = _read_json(self._status_path)
        pid = int(status.get("pid", self._helper_pid) or 0)
        return status.get("state") == "ready" and _process_alive(pid)

    def start(
        self,
        socks_port: int,
        server_host: str,
        mtu: int = 1500,
        split_mode: str = "",
        split_entries: list[str] | None = None,
    ) -> None:
        self.stop()
        if self.is_admin():
            self._direct.start(socks_port, server_host, mtu, split_mode, split_entries)
            return
        port = int(socks_port)
        if not 1 <= port <= 65535:
            raise ValueError("Некорректный локальный SOCKS-порт")
        host = str(server_host).strip()
        if not host or len(host) > 253 or any(ord(char) < 32 for char in host):
            raise ValueError("Некорректный адрес SSH-сервера")
        mode = str(split_mode)
        if mode not in ("", "only", "bypass"):
            raise ValueError("Некорректный режим маршрутизации")
        entries = [str(item)[:512] for item in list(split_entries or [])[:128]]
        APP_DIR.mkdir(parents=True, exist_ok=True)
        token = secrets.token_hex(16)
        request = APP_DIR / f"vpn-helper-{token}-request.json"
        status, stop = _control_paths(request)
        _write_json(request, {
            "token": token,
            "parent_pid": os.getpid(),
            "socks_port": port,
            "server_host": host,
            "mtu": min(1500, max(1280, int(mtu))),
            "split_mode": mode,
            "split_entries": entries,
        })
        self._request_path, self._status_path, self._stop_path = request, status, stop
        try:
            _launch_elevated(request)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                current = _read_json(status)
                state = current.get("state")
                self._helper_pid = int(current.get("pid", 0) or 0)
                if state == "ready" and _process_alive(self._helper_pid):
                    return
                if state == "error":
                    raise RuntimeError(str(current.get("error") or "VPN helper завершился с ошибкой"))
                time.sleep(0.1)
            raise TimeoutError("Системный VPN helper не запустился за 45 секунд")
        except Exception:
            try:
                stop.touch()
            except OSError:
                pass
            self._cleanup_control_files()
            raise

    def stop(self) -> None:
        self._direct.stop()
        if self._stop_path is None:
            return
        try:
            self._stop_path.touch()
        except OSError:
            pass
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            status = _read_json(self._status_path) if self._status_path else {}
            if status.get("state") in ("stopped", "error") or not _process_alive(self._helper_pid):
                break
            time.sleep(0.1)
        self._cleanup_control_files()

    def restore_stale(self) -> None:
        if self.is_admin():
            self._direct.restore_stale()

    def _cleanup_control_files(self) -> None:
        paths = (self._request_path, self._status_path, self._stop_path)
        self._request_path = self._status_path = self._stop_path = None
        self._helper_pid = 0
        for path in paths:
            if path is not None:
                try:
                    path.unlink(missing_ok=True)
                except OSError:
                    pass


def run_tun_helper(request_text: str) -> int:
    request_path = Path(request_text).resolve()
    if request_path.parent != APP_DIR.resolve() or not _REQUEST_PATTERN.fullmatch(request_path.name):
        return 2
    status_path, stop_path = _control_paths(request_path)
    request = _read_json(request_path)
    match = _REQUEST_PATTERN.fullmatch(request_path.name)
    if match is None or request.get("token") != match.group(1):
        return 2
    if not DirectWindowsTunManager.is_admin():
        _write_json(status_path, {"state": "error", "pid": os.getpid(), "error": "VPN helper не получил права администратора"})
        return 3
    manager = DirectWindowsTunManager()
    state = "stopped"
    try:
        _write_json(status_path, {"state": "starting", "pid": os.getpid()})
        manager.restore_stale()
        socks_port = int(request["socks_port"])
        if not _socks_listener_ready(socks_port):
            raise RuntimeError("Локальный SSH SOCKS не запущен")
        manager.start(
            socks_port,
            str(request["server_host"]),
            int(request.get("mtu", 1500)),
            str(request.get("split_mode", "")),
            [str(item) for item in list(request.get("split_entries") or [])],
        )
        _write_json(status_path, {"state": "ready", "pid": os.getpid()})
        parent_pid = int(request.get("parent_pid", 0))
        socks_failures = 0
        while not stop_path.exists() and _process_alive(parent_pid) and manager.is_active():
            time.sleep(0.2)
            socks_failures = 0 if _socks_listener_ready(socks_port) else socks_failures + 1
            if socks_failures >= 3:
                state = "error"
                _write_json(status_path, {
                    "state": state,
                    "pid": os.getpid(),
                    "error": "SSH SOCKS перестал отвечать; VPN-маршруты сняты",
                })
                return 5
        if not manager.is_active() and not stop_path.exists():
            state = "error"
            _write_json(status_path, {"state": state, "pid": os.getpid(), "error": "Компонент tun2socks неожиданно завершился"})
            return 4
        return 0
    except Exception as error:
        state = "error"
        _write_json(status_path, {"state": state, "pid": os.getpid(), "error": str(error)})
        return 1
    finally:
        manager.stop()
        if state != "error":
            _write_json(status_path, {"state": "stopped", "pid": os.getpid()})
