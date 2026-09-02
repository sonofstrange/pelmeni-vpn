from __future__ import annotations

import json
import socket
import threading
import time
from typing import Any

from .service import _read_endpoint


class ServiceClient:
    """Client for communicating with the elevated PelmeniVPNService without UAC prompts."""

    def __init__(self) -> None:
        self._sock: socket.socket | None = None
        self._rfile: Any = None
        self._wfile: Any = None
        self._token: str = ""
        self._heartbeat_thread: threading.Thread | None = None
        self._stop_heartbeat = threading.Event()
        self._lock = threading.RLock()

    @staticmethod
    def is_service_available() -> bool:
        endpoint = _read_endpoint()
        if not endpoint:
            return False
        port = int(endpoint.get("port", 0))
        token = str(endpoint.get("token", ""))
        if not port or not token:
            return False
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1.0) as s:
                s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                req = (json.dumps({"cmd": "ping", "token": token}) + "\n").encode("utf-8")
                s.sendall(req)
                data = s.recv(1024).decode("utf-8", errors="replace")
                res = json.loads(data.strip().splitlines()[0])
                return res.get("status") == "ok"
        except Exception:
            return False

    def connect(self) -> bool:
        with self._lock:
            if self._sock is not None:
                return True
            endpoint = _read_endpoint()
            if not endpoint:
                return False
            port = int(endpoint.get("port", 0))
            self._token = str(endpoint.get("token", ""))
            try:
                s = socket.create_connection(("127.0.0.1", port), timeout=3.0)
                s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self._sock = s
                return True
            except Exception:
                self._cleanup()
                return False

    def _send_cmd(self, cmd: str, **kwargs: Any) -> dict[str, Any]:
        with self._lock:
            if not self.connect() or self._sock is None:
                raise RuntimeError("Фоновая служба PelmeniVPNService недоступна")
            payload = {"cmd": cmd, "token": self._token, **kwargs}
            raw = (json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8")
            try:
                self._sock.sendall(raw)
                data = b""
                while b"\n" not in data:
                    chunk = self._sock.recv(4096)
                    if not chunk:
                        raise ConnectionResetError("Служба разорвала соединение")
                    data += chunk
                line = data.decode("utf-8", errors="replace").strip().splitlines()[0]
                return json.loads(line)
            except Exception as e:
                self._cleanup()
                raise RuntimeError(f"Ошибка связи со службой VPN: {e}") from e

    def start_vpn(
        self,
        socks_port: int,
        server_host: str,
        mtu: int = 1500,
        split_mode: str = "",
        split_entries: list[str] | None = None,
    ) -> None:
        res = self._send_cmd(
            "start",
            socks_port=socks_port,
            server_host=server_host,
            mtu=mtu,
            split_mode=split_mode,
            split_entries=split_entries or [],
        )
        if res.get("status") != "ready":
            raise RuntimeError(res.get("error") or "Служба не смогла запустить VPN")
        self._start_heartbeat()

    def stop_vpn(self) -> None:
        self._stop_heartbeat_loop()
        try:
            self._send_cmd("stop")
        except Exception:
            pass
        finally:
            self._cleanup()

    def is_active(self) -> bool:
        if self._sock is None:
            return False
        try:
            res = self._send_cmd("status")
            return res.get("status") == "ready" and res.get("active") is True
        except Exception:
            return False

    def _start_heartbeat(self) -> None:
        self._stop_heartbeat.clear()
        self._heartbeat_thread = threading.Thread(target=self._heartbeat_loop, daemon=True)
        self._heartbeat_thread.start()

    def _stop_heartbeat_loop(self) -> None:
        self._stop_heartbeat.set()
        if self._heartbeat_thread and self._heartbeat_thread.is_alive():
            self._heartbeat_thread.join(timeout=1.0)
        self._heartbeat_thread = None

    def _heartbeat_loop(self) -> None:
        while not self._stop_heartbeat.is_set():
            time.sleep(2.0)
            if self._stop_heartbeat.is_set():
                break
            try:
                self._send_cmd("heartbeat")
            except Exception:
                break

    def close(self) -> None:
        self._stop_heartbeat_loop()
        self._cleanup()

    def _cleanup(self) -> None:
        if self._rfile:
            try:
                self._rfile.close()
            except Exception:
                pass
            self._rfile = None
        if self._wfile:
            try:
                self._wfile.close()
            except Exception:
                pass
            self._wfile = None
        if self._sock:
            try:
                self._sock.close()
            except Exception:
                pass
            self._sock = None
