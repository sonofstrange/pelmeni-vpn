from __future__ import annotations

import ctypes
import json
import os
import ipaddress
import re
import winreg
from typing import Any

from .storage import APP_DIR, PROXY_BACKUP_PATH


INTERNET_SETTINGS = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings"
MANAGED_VALUES = ("ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL")
PAC_PATH = APP_DIR / "split-proxy.pac"


def _notify_windows() -> None:
    internet_set_option = ctypes.windll.wininet.InternetSetOptionW
    internet_set_option(None, 39, None, 0)
    internet_set_option(None, 37, None, 0)


def _read_value(key: Any, name: str) -> dict[str, Any]:
    try:
        value, value_type = winreg.QueryValueEx(key, name)
        return {"exists": True, "value": value, "type": value_type}
    except FileNotFoundError:
        return {"exists": False}


def _write_backup(backup: dict[str, Any]) -> None:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    PROXY_BACKUP_PATH.write_text(
        json.dumps(backup, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def _clean_domain(value: str) -> str:
    domain = value.removeprefix("https://").removeprefix("http://").split("/", 1)[0]
    domain = domain.removeprefix("*.").strip(".").lower()
    if not domain or len(domain) > 253 or not re.fullmatch(r"[a-z0-9.-]+", domain):
        return ""
    if any(not label or len(label) > 63 or label.startswith("-") or label.endswith("-") for label in domain.split(".")):
        return ""
    return domain

def enable_socks_proxy(port: int, split_mode: str = "", entries: list[str] | None = None) -> None:
    if os.name != "nt":
        raise RuntimeError("Системный прокси поддерживается только в Windows")
    with winreg.OpenKey(
        winreg.HKEY_CURRENT_USER,
        INTERNET_SETTINGS,
        0,
        winreg.KEY_READ | winreg.KEY_WRITE,
    ) as key:
        if not PROXY_BACKUP_PATH.exists():
            _write_backup({name: _read_value(key, name) for name in MANAGED_VALUES})
        cleaned = [str(item).strip() for item in (entries or []) if str(item).strip()]
        if split_mode == "only" and cleaned:
            rules = []
            for item in cleaned:
                try:
                    network = ipaddress.ip_network(item, strict=False)
                    if network.version == 4:
                        rules.append(
                            f'isInNet(dnsResolve(host), "{network.network_address}", "{network.netmask}")'
                        )
                        continue
                except ValueError:
                    pass
                domain = _clean_domain(item)
                if domain:
                    rules.append(f'dnsDomainIs(host, ".{domain}") || host == "{domain}"')
            condition = " || ".join(f"({rule})" for rule in rules) or "false"
            APP_DIR.mkdir(parents=True, exist_ok=True)
            PAC_PATH.write_text(
                "function FindProxyForURL(url, host) {\n"
                f"  if ({condition}) return 'SOCKS5 127.0.0.1:{int(port)}; SOCKS 127.0.0.1:{int(port)}';\n"
                "  return 'DIRECT';\n}\n",
                encoding="utf-8",
            )
            winreg.SetValueEx(key, "ProxyEnable", 0, winreg.REG_DWORD, 0)
            winreg.SetValueEx(key, "AutoConfigURL", 0, winreg.REG_SZ, PAC_PATH.as_uri())
        else:
            try:
                winreg.DeleteValue(key, "AutoConfigURL")
            except FileNotFoundError:
                pass
            bypass = ["<local>", "localhost", "127.*", "10.*", "172.16.*", "192.168.*"]
            if split_mode == "bypass":
                for item in cleaned:
                    domain = _clean_domain(item)
                    if domain:
                        bypass.extend([domain, "*." + domain])
            winreg.SetValueEx(key, "ProxyEnable", 0, winreg.REG_DWORD, 1)
            winreg.SetValueEx(key, "ProxyServer", 0, winreg.REG_SZ, f"socks=127.0.0.1:{int(port)}")
            winreg.SetValueEx(key, "ProxyOverride", 0, winreg.REG_SZ, ";".join(dict.fromkeys(bypass)))
    _notify_windows()


def restore_proxy() -> bool:
    if os.name != "nt" or not PROXY_BACKUP_PATH.exists():
        return False
    backup = json.loads(PROXY_BACKUP_PATH.read_text(encoding="utf-8"))
    with winreg.OpenKey(
        winreg.HKEY_CURRENT_USER,
        INTERNET_SETTINGS,
        0,
        winreg.KEY_READ | winreg.KEY_WRITE,
    ) as key:
        for name in MANAGED_VALUES:
            state = backup.get(name, {"exists": False})
            if state.get("exists"):
                winreg.SetValueEx(
                    key,
                    name,
                    0,
                    int(state["type"]),
                    state.get("value"),
                )
            else:
                try:
                    winreg.DeleteValue(key, name)
                except FileNotFoundError:
                    pass
    PROXY_BACKUP_PATH.unlink(missing_ok=True)
    PAC_PATH.unlink(missing_ok=True)
    _notify_windows()
    return True


def restore_stale_proxy() -> bool:
    try:
        return restore_proxy()
    except Exception:
        return False
