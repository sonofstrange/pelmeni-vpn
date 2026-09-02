from __future__ import annotations

import argparse
import ctypes
import os
import socket
import socketserver
import struct
import threading
from .socks_vpn import SocksProxy


class _EchoHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        while True:
            data = self.request.recv(4096)
            if not data:
                return
            self.request.sendall(data)


class _DirectSocketTransport:
    def __init__(self) -> None:
        self.active = True

    def is_active(self) -> bool:
        return self.active

    def open_channel(self, _kind, target, _origin, timeout=20):
        return socket.create_connection(target, timeout=timeout)


def _self_test() -> None:

    echo = socketserver.ThreadingTCPServer(("127.0.0.1", 0), _EchoHandler)
    echo.daemon_threads = True
    echo_thread = threading.Thread(target=echo.serve_forever, daemon=True)
    echo_thread.start()
    transport = _DirectSocketTransport()
    proxy = SocksProxy(transport, 0)
    proxy.start()
    try:
        with socket.create_connection(("127.0.0.1", proxy.port), timeout=5) as stream:
            stream.sendall(b"\x05\x01\x00")
            if stream.recv(2) != b"\x05\x00":
                raise RuntimeError("SOCKS5 method negotiation failed")
            host = b"127.0.0.1"
            stream.sendall(
                b"\x05\x01\x00\x03"
                + bytes([len(host)])
                + host
                + struct.pack("!H", echo.server_address[1])
            )
            reply = stream.recv(10)
            if len(reply) < 2 or reply[1] != 0:
                raise RuntimeError("SOCKS5 CONNECT failed")
            stream.sendall(b"pelmeni")
            if stream.recv(7) != b"pelmeni":
                raise RuntimeError("SOCKS relay failed")
        with socket.create_connection(("127.0.0.1", proxy.port), timeout=5) as stream:
            stream.sendall(
                b"\x04\x01"
                + struct.pack("!H", echo.server_address[1])
                + socket.inet_aton("127.0.0.1")
                + b"pelmeni\x00"
            )
            reply = stream.recv(8)
            if len(reply) < 2 or reply[1] != 0x5A:
                raise RuntimeError("SOCKS4 CONNECT failed")
            stream.sendall(b"desktop")
            if stream.recv(7) != b"desktop":
                raise RuntimeError("SOCKS4 relay failed")
    finally:
        transport.active = False
        proxy.stop()
        echo.shutdown()
        echo.server_close()
    print("Pelmeni VPN Desktop self-test: OK")


def _enable_dpi_awareness() -> None:
    if os.name != "nt":
        return
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


def main() -> None:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--self-test", action="store_true")
    args, _ = parser.parse_known_args()
    if args.self_test:
        _self_test()
        return
    _enable_dpi_awareness()
    import tkinter as tk
    from .ui import PelmeniDesktopApp
    root = tk.Tk()
    PelmeniDesktopApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
