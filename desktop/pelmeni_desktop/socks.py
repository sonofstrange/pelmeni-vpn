from __future__ import annotations

import ipaddress
import select
import socket
import socketserver
import struct
import threading
from typing import Any


def _recv_exact(stream: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = stream.recv(size - len(data))
        if not chunk:
            raise ConnectionError("Соединение закрыто")
        data.extend(chunk)
    return bytes(data)


def _recv_until_null(stream: socket.socket, limit: int = 4096) -> bytes:
    data = bytearray()
    while len(data) < limit:
        value = _recv_exact(stream, 1)
        if value == b"\x00":
            return bytes(data)
        data.extend(value)
    raise ValueError("Слишком длинное поле SOCKS")


class _ThreadingSocksServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address: tuple[str, int], transport: Any):
        super().__init__(address, _SocksHandler)
        self.transport = transport
        self.uploaded = 0
        self.downloaded = 0
        self.counter_lock = threading.Lock()

    def add_traffic(self, uploaded: int = 0, downloaded: int = 0) -> None:
        with self.counter_lock:
            self.uploaded += uploaded
            self.downloaded += downloaded

    def traffic(self) -> tuple[int, int]:
        with self.counter_lock:
            return self.uploaded, self.downloaded


class _SocksHandler(socketserver.BaseRequestHandler):
    request: socket.socket
    server: _ThreadingSocksServer

    def handle(self) -> None:
        self.request.settimeout(20)
        channel = None
        try:
            version = _recv_exact(self.request, 1)
            if version == b"\x05":
                target = self._handshake_v5()
                reply = self._reply_v5
            elif version == b"\x04":
                target = self._handshake_v4()
                reply = self._reply_v4
            else:
                return
            channel = self.server.transport.open_channel(
                "direct-tcpip",
                target,
                self.client_address,
                timeout=20,
            )
            if channel is None:
                raise ConnectionError("SSH-сервер отклонил канал")
            reply(True)
            self.request.settimeout(None)
            self._relay(channel)
        except Exception:
            try:
                if "reply" in locals():
                    reply(False)
            except Exception:
                pass
        finally:
            if channel is not None:
                try:
                    channel.close()
                except Exception:
                    pass

    def _handshake_v5(self) -> tuple[str, int]:
        method_count = _recv_exact(self.request, 1)[0]
        methods = _recv_exact(self.request, method_count)
        if 0 not in methods:
            self.request.sendall(b"\x05\xff")
            raise PermissionError("SOCKS5-клиент требует авторизацию")
        self.request.sendall(b"\x05\x00")
        version, command, _, address_type = _recv_exact(self.request, 4)
        if version != 5 or command != 1:
            raise ValueError("Поддерживается только SOCKS5 CONNECT")
        if address_type == 1:
            host = socket.inet_ntoa(_recv_exact(self.request, 4))
        elif address_type == 3:
            length = _recv_exact(self.request, 1)[0]
            host = _recv_exact(self.request, length).decode("idna")
        elif address_type == 4:
            host = socket.inet_ntop(socket.AF_INET6, _recv_exact(self.request, 16))
        else:
            raise ValueError("Неизвестный тип SOCKS5-адреса")
        port = struct.unpack("!H", _recv_exact(self.request, 2))[0]
        return host, port

    def _reply_v5(self, success: bool) -> None:
        code = 0 if success else 5
        self.request.sendall(
            b"\x05" + bytes([code]) + b"\x00\x01\x00\x00\x00\x00\x00\x00"
        )

    def _handshake_v4(self) -> tuple[str, int]:
        command = _recv_exact(self.request, 1)[0]
        if command != 1:
            raise ValueError("Поддерживается только SOCKS4 CONNECT")
        port = struct.unpack("!H", _recv_exact(self.request, 2))[0]
        raw_address = _recv_exact(self.request, 4)
        _recv_until_null(self.request)
        address = ipaddress.ip_address(raw_address)
        if raw_address[:3] == b"\x00\x00\x00" and raw_address[3] != 0:
            host = _recv_until_null(self.request).decode("idna")
        else:
            host = str(address)
        return host, port

    def _reply_v4(self, success: bool) -> None:
        self.request.sendall(
            b"\x00"
            + (b"\x5a" if success else b"\x5b")
            + b"\x00\x00\x00\x00\x00\x00"
        )

    def _relay(self, channel: Any) -> None:
        while self.server.transport.is_active():
            readable, _, _ = select.select([self.request, channel], [], [], 1)
            if self.request in readable:
                data = self.request.recv(65536)
                if not data:
                    break
                channel.sendall(data)
                self.server.add_traffic(uploaded=len(data))
            if channel in readable:
                data = channel.recv(65536)
                if not data:
                    break
                self.request.sendall(data)
                self.server.add_traffic(downloaded=len(data))


class SocksProxy:
    def __init__(self, transport: Any, port: int):
        self._server = _ThreadingSocksServer(("127.0.0.1", int(port)), transport)
        self._thread = threading.Thread(
            target=self._server.serve_forever,
            name="pelmeni-socks",
            daemon=True,
        )

    @property
    def port(self) -> int:
        return int(self._server.server_address[1])

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=3)

    def traffic(self) -> tuple[int, int]:
        return self._server.traffic()
