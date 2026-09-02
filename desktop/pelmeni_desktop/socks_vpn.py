from __future__ import annotations

import ipaddress
import select
import socket
import socketserver
import struct
import threading
import time
import traceback
from typing import Any

from .storage import APP_DIR

from .socks import _recv_exact, _recv_until_null


def _log_error(error: Exception) -> None:
    try:
        APP_DIR.mkdir(parents=True, exist_ok=True)
        with (APP_DIR / "socks.log").open("a", encoding="utf-8") as log:
            traceback.print_exception(type(error), error, error.__traceback__, file=log)
    except OSError:
        pass

def _recv_channel_exact(channel: Any, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = channel.recv(size - len(data))
        if not chunk:
            raise EOFError("SSH-канал DNS закрылся раньше ответа")
        data.extend(chunk)
    return bytes(data)


_DNS_CACHE: dict[bytes, tuple[float, bytes]] = {}
_DNS_CACHE_LOCK = threading.Lock()
_DNS_CACHE_TTL = 45.0


def _dns_over_ssh(transport: Any, payload: bytes) -> bytes:
    """Resolve a DNS wire query through an IPv4 TCP channel inside SSH with TTL caching."""
    if not payload or len(payload) > 65535:
        raise ValueError("Некорректный DNS-запрос")

    # The first 2 bytes are DNS transaction ID. The remaining bytes are the query.
    query_key = payload[2:] if len(payload) > 2 else payload
    now = time.monotonic()

    with _DNS_CACHE_LOCK:
        if query_key in _DNS_CACHE:
            ts, cached_answer = _DNS_CACHE[query_key]
            if now - ts < _DNS_CACHE_TTL and len(cached_answer) >= 2:
                return payload[:2] + cached_answer[2:]
            else:
                _DNS_CACHE.pop(query_key, None)

    channel = transport.open_channel(
        "direct-tcpip", ("1.1.1.1", 53), ("127.0.0.1", 0), timeout=8
    )
    if channel is None:
        raise ConnectionError("SSH-сервер отклонил DNS-канал")
    try:
        channel.sendall(struct.pack("!H", len(payload)) + payload)
        size = struct.unpack("!H", _recv_channel_exact(channel, 2))[0]
        answer = _recv_channel_exact(channel, size)
        with _DNS_CACHE_LOCK:
            if len(_DNS_CACHE) > 512:
                _DNS_CACHE.clear()
            _DNS_CACHE[query_key] = (now, answer)
        return answer
    finally:
        channel.close()


def _parse_udp_packet(packet: bytes) -> tuple[tuple[str, int], bytes, bytes]:
    if len(packet) < 7 or packet[:2] != b"\x00\x00" or packet[2] != 0:
        raise ValueError("Некорректный SOCKS5 UDP-пакет")
    address_type = packet[3]
    offset = 4
    if address_type == 1:
        if len(packet) < offset + 4 + 2:
            raise ValueError("Неполный IPv4 UDP-пакет")
        host = socket.inet_ntoa(packet[offset:offset + 4])
        offset += 4
    elif address_type == 3:
        if len(packet) < offset + 1:
            raise ValueError("Неполный доменный UDP-пакет")
        length = packet[offset]
        offset += 1
        if len(packet) < offset + length + 2:
            raise ValueError("Неполный доменный UDP-пакет")
        host = packet[offset:offset + length].decode("idna")
        offset += length
    elif address_type == 4:
        if len(packet) < offset + 16 + 2:
            raise ValueError("Неполный IPv6 UDP-пакет")
        host = socket.inet_ntop(socket.AF_INET6, packet[offset:offset + 16])
        offset += 16
    else:
        raise ValueError("Неизвестный SOCKS5 UDP address type")
    port = struct.unpack("!H", packet[offset:offset + 2])[0]
    offset += 2
    return (host, port), packet[offset:], packet[:offset]


class _Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    MAX_CONCURRENT_CLIENTS = 128

    def __init__(self, address: tuple[str, int], transport: Any):
        super().__init__(address, _Handler)
        self.transport = transport
        self.uploaded = 0
        self.downloaded = 0
        self.last_latency_ms = -1
        self.counter_lock = threading.Lock()
        self.client_slots = threading.BoundedSemaphore(self.MAX_CONCURRENT_CLIENTS)

    def add_traffic(self, uploaded: int = 0, downloaded: int = 0) -> None:
        with self.counter_lock:
            self.uploaded += uploaded
            self.downloaded += downloaded

    def traffic(self) -> tuple[int, int]:
        with self.counter_lock:
            return self.uploaded, self.downloaded


class _Handler(socketserver.BaseRequestHandler):
    request: socket.socket
    server: _Server

    def handle(self) -> None:
        if not self.server.client_slots.acquire(timeout=5.0):
            try:
                self.request.close()
            except Exception:
                pass
            return
        try:
            self._handle_connection()
        finally:
            self.server.client_slots.release()

    def _handle_connection(self) -> None:
        self.request.settimeout(20)
        try:
            self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.request.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 256 * 1024)
            self.request.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 256 * 1024)
        except OSError:
            pass
        channel = None
        reply = None
        try:
            version = _recv_exact(self.request, 1)
            if version == b"\x05":
                command, target = self._handshake_v5()
                if command == 3:
                    self._relay_udp()
                    return
                reply = self._reply_v5
            elif version == b"\x04":
                target = self._handshake_v4()
                reply = self._reply_v4
            else:
                return
            if target[1] == 53:
                reply(True)
                self.request.settimeout(15)
                self._relay_dns_tcp()
                return
            t0 = time.perf_counter()
            channel = self.server.transport.open_channel(
                "direct-tcpip", target, self.client_address, timeout=15
            )
            if channel is None:
                raise ConnectionError("SSH-сервер отклонил канал")
            elapsed = max(1, round((time.perf_counter() - t0) * 1000))
            self.server.last_latency_ms = elapsed
            reply(True)
            self.request.settimeout(None)
            self._relay_tcp(channel)
        except (EOFError, ConnectionError, ConnectionAbortedError, ConnectionResetError, OSError):
            pass
        except Exception as error:
            if error.__class__.__name__ == "SSHException":
                pass
            else:
                _log_error(error)
            if reply is not None:
                try:
                    reply(False)
                except Exception:
                    pass
        finally:
            if channel is not None:
                try:
                    channel.close()
                except Exception:
                    pass

    def _relay_dns_tcp(self) -> None:
        while self.server.transport.is_active():
            try:
                size = struct.unpack("!H", _recv_exact(self.request, 2))[0]
                payload = _recv_exact(self.request, size)
            except (ConnectionError, TimeoutError, OSError):
                return
            answer = _dns_over_ssh(self.server.transport, payload)
            self.request.sendall(struct.pack("!H", len(answer)) + answer)
            self.server.add_traffic(
                uploaded=len(payload) + 2,
                downloaded=len(answer) + 2,
            )
    def _handshake_v5(self) -> tuple[int, tuple[str, int]]:
        method_count = _recv_exact(self.request, 1)[0]
        methods = _recv_exact(self.request, method_count)
        if 0 not in methods:
            self.request.sendall(b"\x05\xff")
            raise PermissionError("SOCKS5-клиент требует авторизацию")
        self.request.sendall(b"\x05\x00")
        version, command, _, address_type = _recv_exact(self.request, 4)
        if version != 5 or command not in (1, 3):
            raise ValueError("Поддерживаются SOCKS5 CONNECT и UDP ASSOCIATE")
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
        return command, (host, port)

    def _reply_v5(self, success: bool) -> None:
        self.request.sendall(
            b"\x05" + (b"\x00" if success else b"\x05") + b"\x00\x01\x00\x00\x00\x00\x00\x00"
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
            b"\x00" + (b"\x5a" if success else b"\x5b") + b"\x00\x00\x00\x00\x00\x00"
        )

    def _relay_tcp(self, channel: Any) -> None:
        client_sock = self.request
        stop_event = threading.Event()

        def copy_up() -> None:
            try:
                while not stop_event.is_set() and self.server.transport.is_active():
                    data = client_sock.recv(65536)
                    if not data:
                        break
                    channel.sendall(data)
                    self.server.add_traffic(uploaded=len(data))
            except Exception:
                pass
            finally:
                stop_event.set()
                try:
                    channel.shutdown_write()
                except Exception:
                    pass

        def copy_down() -> None:
            try:
                while not stop_event.is_set() and self.server.transport.is_active():
                    data = channel.recv(65536)
                    if not data:
                        break
                    client_sock.sendall(data)
                    self.server.add_traffic(downloaded=len(data))
            except Exception:
                pass
            finally:
                stop_event.set()
                try:
                    client_sock.shutdown(socket.SHUT_WR)
                except Exception:
                    pass

        up_thread = threading.Thread(target=copy_up, daemon=True)
        up_thread.start()
        copy_down()
        up_thread.join(timeout=1.0)

    def _relay_udp(self) -> None:
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.bind(("127.0.0.1", 0))
        try:
            self.request.sendall(
                b"\x05\x00\x00\x01"
                + socket.inet_aton("127.0.0.1")
                + struct.pack("!H", int(udp.getsockname()[1]))
            )
            self.request.settimeout(None)
            udp.settimeout(0.5)
            while self.server.transport.is_active():
                try:
                    packet, source = udp.recvfrom(65535)
                except (socket.timeout, TimeoutError):
                    try:
                        readable, _, _ = select.select([self.request], [], [], 0)
                        if readable and not self.request.recv(1, socket.MSG_PEEK):
                            break
                    except Exception:
                        break
                    continue
                except OSError:
                    break

                if source[0] != self.client_address[0]:
                    continue
                try:
                    target, payload, response_header = _parse_udp_packet(packet)
                except ValueError:
                    continue
                # DNS is converted to TCP/53 inside the same SSH tunnel.
                # Other UDP (notably QUIC) is dropped so applications use TCP.
                if target[1] != 53 or not payload or len(payload) > 65535:
                    continue
                try:
                    answer = _dns_over_ssh(self.server.transport, payload)
                    udp.sendto(response_header + answer, source)
                    self.server.add_traffic(
                        uploaded=len(packet), downloaded=len(response_header) + len(answer)
                    )
                except Exception as error:
                    _log_error(error)
        finally:
            udp.close()



class SocksProxy:
    def __init__(self, transport: Any, port: int):
        self._server = _Server(("127.0.0.1", int(port)), transport)
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
