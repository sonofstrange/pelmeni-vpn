from __future__ import annotations

import base64
import ctypes
import json
import os
from ctypes import wintypes
from pathlib import Path
from typing import Any


APP_DIR = Path(os.environ.get("APPDATA", Path.home())) / "PelmeniVPN Desktop"
CONFIG_PATH = APP_DIR / "config.json"
KNOWN_HOSTS_PATH = APP_DIR / "known_hosts"
PROXY_BACKUP_PATH = APP_DIR / "proxy-backup.json"


class _DataBlob(ctypes.Structure):
    _fields_ = [
        ("cbData", wintypes.DWORD),
        ("pbData", ctypes.POINTER(ctypes.c_byte)),
    ]


_CryptProtectData = ctypes.windll.crypt32.CryptProtectData
_CryptProtectData.argtypes = [
    ctypes.POINTER(_DataBlob),
    wintypes.LPCWSTR,
    ctypes.POINTER(_DataBlob),
    ctypes.c_void_p,
    ctypes.c_void_p,
    wintypes.DWORD,
    ctypes.POINTER(_DataBlob),
]
_CryptProtectData.restype = wintypes.BOOL

_CryptUnprotectData = ctypes.windll.crypt32.CryptUnprotectData
_CryptUnprotectData.argtypes = [
    ctypes.POINTER(_DataBlob),
    ctypes.POINTER(wintypes.LPWSTR),
    ctypes.POINTER(_DataBlob),
    ctypes.c_void_p,
    ctypes.c_void_p,
    wintypes.DWORD,
    ctypes.POINTER(_DataBlob),
]
_CryptUnprotectData.restype = wintypes.BOOL

_LocalFree = ctypes.windll.kernel32.LocalFree
_LocalFree.argtypes = [wintypes.HLOCAL]
_LocalFree.restype = wintypes.HLOCAL


def _blob(data: bytes) -> tuple[_DataBlob, Any]:
    buffer = ctypes.create_string_buffer(data)
    return (
        _DataBlob(
            len(data),
            ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)),
        ),
        buffer,
    )


def protect_secret(value: str) -> str:
    if not value:
        return ""
    if os.name != "nt":
        raise RuntimeError("DPAPI доступен только в Windows")
    source, source_buffer = _blob(value.encode("utf-8"))
    output = _DataBlob()
    ok = _CryptProtectData(
        ctypes.byref(source),
        "Pelmeni VPN Desktop",
        None,
        None,
        None,
        0,
        ctypes.byref(output),
    )
    del source_buffer
    if not ok:
        raise ctypes.WinError()
    try:
        encrypted = ctypes.string_at(output.pbData, output.cbData)
        return base64.b64encode(encrypted).decode("ascii")
    finally:
        _LocalFree(output.pbData)


def unprotect_secret(value: str) -> str:
    if not value:
        return ""
    if os.name != "nt":
        raise RuntimeError("DPAPI доступен только в Windows")
    source, source_buffer = _blob(base64.b64decode(value.encode("ascii")))
    output = _DataBlob()
    ok = _CryptUnprotectData(
        ctypes.byref(source),
        None,
        None,
        None,
        None,
        0,
        ctypes.byref(output),
    )
    del source_buffer
    if not ok:
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output.pbData, output.cbData).decode("utf-8")
    finally:
        _LocalFree(output.pbData)


DEFAULT_CONFIG: dict[str, Any] = {
    "profile": {
        "name": "Мой сервер",
        "host": "",
        "port": 22,
        "username": "root",
        "password": "",
        "socks_port": 1080,
    },
    "last_proxy_mode": True,
    "last_vpn_mode": False,
    "auto_reconnect": True,
    "auto_server_failover": False,
    "start_on_boot": False,
    "beta_updates": False,
    "split_enabled": False,
    "split_mode": "bypass",
    "split_entries": [],
    "app_split_enabled": False,
    "app_split_mode": "bypass",
    "app_split_apps": [],
    "total_down": 0,
    "total_up": 0,
}


def _merged_config(raw: dict[str, Any]) -> dict[str, Any]:
    config = {
        "profile": dict(DEFAULT_CONFIG["profile"]),
        "last_proxy_mode": bool(raw.get("last_proxy_mode", True)),
        "last_vpn_mode": bool(raw.get("last_vpn_mode", False)),
        "auto_reconnect": bool(raw.get("auto_reconnect", True)),
        "auto_server_failover": bool(raw.get("auto_server_failover", False)),
        "start_on_boot": bool(raw.get("start_on_boot", False)),
        "beta_updates": bool(raw.get("beta_updates", False)),
        "split_enabled": bool(raw.get("split_enabled", False)),
        "split_mode": str(raw.get("split_mode", "bypass")),
        "split_entries": list(raw.get("split_entries") or []),
        "app_split_enabled": bool(raw.get("app_split_enabled", False)),
        "app_split_mode": str(raw.get("app_split_mode", "bypass")),
        "app_split_apps": list(raw.get("app_split_apps") or []),
        "total_down": int(raw.get("total_down", 0)),
        "total_up": int(raw.get("total_up", 0)),
    }
    profile = raw.get("profile")
    if isinstance(profile, dict):
        config["profile"].update(profile)
    encrypted = str(config["profile"].pop("password_protected", "") or "")
    if encrypted:
        try:
            config["profile"]["password"] = unprotect_secret(encrypted)
        except Exception:
            config["profile"]["password"] = ""
    return config


def load_config() -> dict[str, Any]:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    if not CONFIG_PATH.exists():
        return _merged_config({})
    try:
        raw = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        return _merged_config(raw if isinstance(raw, dict) else {})
    except Exception:
        return _merged_config({})


def save_config(config: dict[str, Any]) -> None:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    profile = dict(config.get("profile") or {})
    password = str(profile.pop("password", "") or "")
    profile["password_protected"] = protect_secret(password) if password else ""
    payload = {
        "profile": profile,
        "last_proxy_mode": bool(config.get("last_proxy_mode", True)),
        "last_vpn_mode": bool(config.get("last_vpn_mode", False)),
        "auto_reconnect": bool(config.get("auto_reconnect", True)),
        "auto_server_failover": bool(config.get("auto_server_failover", False)),
        "start_on_boot": bool(config.get("start_on_boot", False)),
        "beta_updates": bool(config.get("beta_updates", False)),
        "split_enabled": bool(config.get("split_enabled", False)),
        "split_mode": str(config.get("split_mode", "bypass")),
        "split_entries": list(config.get("split_entries") or []),
        "app_split_enabled": bool(config.get("app_split_enabled", False)),
        "app_split_mode": str(config.get("app_split_mode", "bypass")),
        "app_split_apps": list(config.get("app_split_apps") or []),
        "total_down": int(config.get("total_down", 0)),
        "total_up": int(config.get("total_up", 0)),
    }
    temporary = CONFIG_PATH.with_suffix(".tmp")
    temporary.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    temporary.replace(CONFIG_PATH)
