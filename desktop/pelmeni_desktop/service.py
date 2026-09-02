from __future__ import annotations

import json
import os
import secrets
import select
import socket
import socketserver
import sys
import threading
import time
from pathlib import Path
from typing import Any

from .storage import APP_DIR
from .windows_tun import WindowsTunManager as DirectWindowsTunManager


SERVICE_NAME = "PelmeniVPNService"
SERVICE_DISPLAY_NAME = "Pelmeni VPN Helper Service"
SERVICE_DESCRIPTION = "Фоновая системная служба Пельмени VPN для управления Wintun без запросов UAC."

DEFAULT_IPC_PORT = 45389
PROGRAMDATA_DIR = Path(os.environ.get("PROGRAMDATA", r"C:\ProgramData")) / "PelmeniVPN"
ENDPOINT_FILE = PROGRAMDATA_DIR / "service_endpoint.json"


def _write_endpoint(port: int, token: str) -> None:
    data = json.dumps({"port": port, "token": token, "pid": os.getpid()}, ensure_ascii=False)
    try:
        PROGRAMDATA_DIR.mkdir(parents=True, exist_ok=True)
        ENDPOINT_FILE.write_text(data, encoding="utf-8")
    except Exception:
        pass
    try:
        APP_DIR.mkdir(parents=True, exist_ok=True)
        (APP_DIR / "service_endpoint.json").write_text(data, encoding="utf-8")
    except Exception:
        pass


def _read_endpoint() -> dict[str, Any] | None:
    candidates = []
    for path in (ENDPOINT_FILE, APP_DIR / "service_endpoint.json"):
        if path.exists():
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(data, dict) and "port" in data:
                    candidates.append((path.stat().st_mtime, data))
            except Exception:
                pass
    if candidates:
        candidates.sort(key=lambda item: item[0], reverse=True)
        return candidates[0][1]
    return None


def _remove_endpoint() -> None:
    for path in (ENDPOINT_FILE, APP_DIR / "service_endpoint.json"):
        try:
            path.unlink(missing_ok=True)
        except Exception:
            pass


def run_service_worker(port: int = DEFAULT_IPC_PORT, stop_event: threading.Event | None = None) -> None:
    if stop_event is None:
        stop_event = threading.Event()

    token = secrets.token_hex(24)
    tun_manager = DirectWindowsTunManager()
    server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_sock.bind(("127.0.0.1", port))
    server_sock.listen(5)
    server_sock.settimeout(0.2)
    actual_port = server_sock.getsockname()[1]
    _write_endpoint(actual_port, token)

    active_client: list[socket.socket | None] = [None]
    lock = threading.Lock()

    def handle_client(sock: socket.socket) -> None:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.settimeout(0.5)
        buffer = ""
        try:
            while not stop_event.is_set():
                try:
                    data = sock.recv(4096)
                    if not data:
                        break
                    buffer += data.decode("utf-8", errors="replace")
                except (socket.timeout, TimeoutError):
                    continue
                except OSError:
                    break

                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    raw = line.strip()
                    if not raw:
                        continue
                    try:
                        payload = json.loads(raw)
                    except Exception:
                        sock.sendall((json.dumps({"error": "Invalid JSON"}) + "\n").encode("utf-8"))
                        continue

                    if not isinstance(payload, dict):
                        continue

                    cmd = payload.get("cmd")
                    if payload.get("token") != token:
                        sock.sendall((json.dumps({"status": "error", "error": "Unauthorized"}) + "\n").encode("utf-8"))
                        return

                    if cmd == "ping":
                        resp = json.dumps({
                            "status": "ok",
                            "active": tun_manager.is_active(),
                            "admin": _is_admin(),
                        }) + "\n"
                        sock.sendall(resp.encode("utf-8"))
                    elif cmd == "status":
                        sock.sendall((json.dumps({
                            "status": "ready" if tun_manager.is_active() else "stopped",
                            "active": tun_manager.is_active(),
                        }) + "\n").encode("utf-8"))
                    elif cmd == "start":
                        with lock:
                            try:
                                tun_manager.stop()
                                tun_manager.restore_stale()
                                tun_manager.start(
                                    socks_port=int(payload.get("socks_port", 0)),
                                    server_host=str(payload.get("server_host", "")).strip(),
                                    mtu=int(payload.get("mtu", 1500)),
                                    split_mode=str(payload.get("split_mode", "")),
                                    split_entries=list(payload.get("split_entries") or []),
                                )
                                active_client[0] = sock
                                sock.sendall((json.dumps({"status": "ready", "active": True}) + "\n").encode("utf-8"))
                            except Exception as e:
                                sock.sendall((json.dumps({"status": "error", "error": str(e)}) + "\n").encode("utf-8"))
                    elif cmd == "stop":
                        with lock:
                            tun_manager.stop()
                            active_client[0] = None
                            sock.sendall((json.dumps({"status": "stopped", "active": False}) + "\n").encode("utf-8"))
                    elif cmd == "heartbeat":
                        sock.sendall((json.dumps({"status": "ok", "active": tun_manager.is_active()}) + "\n").encode("utf-8"))
                    else:
                        sock.sendall((json.dumps({"status": "error", "error": f"Unknown command: {cmd}"}) + "\n").encode("utf-8"))

        except Exception:
            pass
        finally:
            try:
                sock.close()
            except Exception:
                pass
            with lock:
                if active_client[0] == sock:
                    active_client[0] = None
                    try:
                        tun_manager.stop()
                    except Exception:
                        pass

    try:
        while not stop_event.is_set():
            try:
                client_sock, _ = server_sock.accept()
                threading.Thread(target=handle_client, args=(client_sock,), daemon=True).start()
            except (socket.timeout, TimeoutError):
                continue
            except OSError:
                break
    finally:
        try:
            server_sock.close()
        except Exception:
            pass
        _remove_endpoint()
        try:
            tun_manager.stop()
        except Exception:
            pass


try:
    import win32service
    import win32serviceutil
    import win32event

    class PelmeniWindowsService(win32serviceutil.ServiceFramework):
        _svc_name_ = SERVICE_NAME
        _svc_display_name_ = SERVICE_DISPLAY_NAME
        _svc_description_ = SERVICE_DESCRIPTION

        def __init__(self, args: Any) -> None:
            super().__init__(args)
            self.stop_event = win32event.CreateEvent(None, 0, 0, None)
            self.py_stop_event = threading.Event()

        def SvcStop(self) -> None:
            self.ReportServiceStatus(win32service.SERVICE_STOP_PENDING)
            self.py_stop_event.set()
            win32event.SetEvent(self.stop_event)

        def SvcDoRun(self) -> None:
            self.ReportServiceStatus(win32service.SERVICE_RUNNING)
            run_service_worker(DEFAULT_IPC_PORT, self.py_stop_event)

except ImportError:
    PelmeniWindowsService = None  # type: ignore


def _is_admin() -> bool:
    try:
        import ctypes
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def _elevate_cmd(args: list[str]) -> int:
    import ctypes
    import subprocess
    executable = sys.executable
    params = subprocess.list2cmdline(args)
    shell_execute = ctypes.windll.shell32.ShellExecuteW
    shell_execute.restype = ctypes.c_void_p
    res = shell_execute(None, "runas", executable, params, str(Path(executable).parent), 1)
    return 0 if int(res or 0) > 32 else 1


def install_service() -> int:
    service_exe = Path(sys.executable).with_name("PelmeniVPN-Service.exe")
    if not service_exe.is_file():
        for candidate in (
            Path.cwd() / "PelmeniVPN-Service.exe",
            Path(__file__).resolve().parents[1] / "dist" / "PelmeniVPN-Service.exe",
            Path(__file__).resolve().parents[2] / "release" / "PelmeniVPN-Service.exe",
        ):
            if candidate.is_file():
                service_exe = candidate
                break

    if service_exe.is_file():
        if not _is_admin():
            import ctypes
            shell_execute = ctypes.windll.shell32.ShellExecuteW
            shell_execute.restype = ctypes.c_void_p
            res = shell_execute(None, "runas", str(service_exe), "--install", str(service_exe.parent), 1)
            return 0 if int(res or 0) > 32 else 1
        else:
            import subprocess
            res = subprocess.run([str(service_exe), "--install"], capture_output=True, check=False)
            return res.returncode

    if not _is_admin():
        return _elevate_cmd(["--install-service"])

    import subprocess
    bin_path = f'"{sys.executable}" "{Path(__file__).resolve().parents[1] / "service_entry.py"}" --run-service'
    script = f'''
$name = "{SERVICE_NAME}"
$bin = '{bin_path}'
$svc = Get-Service -Name $name -ErrorAction SilentlyContinue
if ($null -ne $svc) {{
    Stop-Service -Name $name -Force -ErrorAction SilentlyContinue
    & cmd.exe /c "sc.exe config $name binPath= \"$bin\" start= auto DisplayName= \"{SERVICE_DISPLAY_NAME}\""
}} else {{
    New-Service -Name $name -BinaryPathName $bin -StartupType Automatic -DisplayName "{SERVICE_DISPLAY_NAME}" -Description "{SERVICE_DESCRIPTION}"
}}
Start-Service -Name $name -ErrorAction SilentlyContinue
'''
    res = subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
        capture_output=True,
        text=True,
        check=False,
    )
    if res.returncode == 0:
        return 0

    cmd_install = f'sc.exe create {SERVICE_NAME} binPath= "{bin_path}" start= auto DisplayName= "{SERVICE_DISPLAY_NAME}"'
    subprocess.run(["cmd.exe", "/c", cmd_install], check=False)
    subprocess.run(["cmd.exe", "/c", f"sc.exe start {SERVICE_NAME}"], check=False)
    return 0


def uninstall_service() -> int:
    service_exe = Path(sys.executable).with_name("PelmeniVPN-Service.exe")
    if not service_exe.is_file():
        for candidate in (
            Path.cwd() / "PelmeniVPN-Service.exe",
            Path(__file__).resolve().parents[1] / "dist" / "PelmeniVPN-Service.exe",
            Path(__file__).resolve().parents[2] / "release" / "PelmeniVPN-Service.exe",
        ):
            if candidate.is_file():
                service_exe = candidate
                break

    if service_exe.is_file():
        if not _is_admin():
            import ctypes
            shell_execute = ctypes.windll.shell32.ShellExecuteW
            shell_execute.restype = ctypes.c_void_p
            res = shell_execute(None, "runas", str(service_exe), "--uninstall", str(service_exe.parent), 1)
            return 0 if int(res or 0) > 32 else 1
        else:
            import subprocess
            res = subprocess.run([str(service_exe), "--uninstall"], capture_output=True, check=False)
            return res.returncode

    if not _is_admin():
        return _elevate_cmd(["--uninstall-service"])

    import subprocess
    script = f'''
$name = "{SERVICE_NAME}"
Stop-Service -Name $name -Force -ErrorAction SilentlyContinue
& cmd.exe /c "sc.exe delete $name" 2>$null
'''
    subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script], check=False)
    _remove_endpoint()
    return 0
