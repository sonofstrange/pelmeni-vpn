from __future__ import annotations

import json
import socket
import time
import urllib.request
from pathlib import Path

from .storage import APP_DIR, load_config
from .tunnel import TunnelManager
from .tun_broker import ElevatedTunManager as WindowsTunManager


REPORT_PATH = APP_DIR / "vpn-self-test.json"


def _write_report(payload: dict[str, object]) -> None:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    temporary = REPORT_PATH.with_suffix(".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(REPORT_PATH)


def run_vpn_self_test() -> int:
    manager = TunnelManager()
    tun = WindowsTunManager()
    started = time.perf_counter()
    phase = "initialization"
    try:
        profile = load_config()["profile"]
        if not profile.get("host") or not profile.get("password"):
            raise RuntimeError("Активный SSH-профиль не настроен")
        phase = "ssh"
        manager.start(profile, lambda *_: False)
        transport = manager.client.get_transport() if manager.client else None
        peer = str(transport.sock.getpeername()[0]) if transport is not None else str(profile["host"])

        phase = "wintun"
        tun.start(manager.proxy.port, peer, int(profile.get("mtu", 1500)))
        phase = "dns"
        addresses = socket.getaddrinfo("example.com", 443, socket.AF_INET, socket.SOCK_STREAM)
        if not addresses:
            raise RuntimeError("DNS не вернул IPv4-адрес")
        phase = "https"
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        request = urllib.request.Request("https://example.com/", headers={"User-Agent": "PelmeniVPN-SelfTest/1"})
        with opener.open(request, timeout=20) as response:
            if response.status != 200:
                raise RuntimeError(f"HTTPS вернул статус {response.status}")
            response.read(256)
        _write_report({
            "ok": True,
            "phase": "complete",
            "elapsed_seconds": round(time.perf_counter() - started, 3),
        })
        return 0
    except Exception as error:
        _write_report({
            "ok": False,
            "phase": phase,
            "error_type": type(error).__name__,
            "error": str(error),
            "elapsed_seconds": round(time.perf_counter() - started, 3),
        })
        return 1
    finally:
        tun.stop()
        manager.stop()
