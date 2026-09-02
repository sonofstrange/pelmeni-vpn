from __future__ import annotations

import json
import uuid
from pathlib import Path
from typing import Any

from .storage import APP_DIR, load_config, protect_secret, save_config, unprotect_secret


PROFILES_PATH = APP_DIR / "profiles.json"


DEFAULT_WINDOW_KIB = 640
HIGH_LATENCY_WINDOW_KIB = 1280
DEFAULT_PACKET_KIB = 32
DEFAULT_MTU = 8500


def _normalize(profile: dict[str, Any]) -> dict[str, Any]:
    window = int(profile.get("window_kib", DEFAULT_WINDOW_KIB))
    if window in (1024, 2048, 4096, 768, 512):
        window = DEFAULT_WINDOW_KIB
    return {
        "id": str(profile.get("id") or uuid.uuid4()),
        "name": str(profile.get("name") or profile.get("host") or "Мой сервер"),
        "host": str(profile.get("host") or ""),
        "port": int(profile.get("port", profile.get("ssh_port", 22))),
        "username": str(profile.get("username", profile.get("user", "root"))),
        "password": str(profile.get("password") or ""),
        "socks_port": int(profile.get("socks_port", 1080)),
        "window_kib": window,
        "packet_kib": int(profile.get("packet_kib", DEFAULT_PACKET_KIB)),
        "mtu": int(profile.get("mtu", DEFAULT_MTU)),
        "tls_enabled": bool(profile.get("tls_enabled", False)),
        "tls_port": int(profile.get("tls_port", 443)),
        "tls_ports": str(profile.get("tls_ports", profile.get("tls_port", 443))),
        "tls_host": str(profile.get("tls_host", profile.get("host", ""))),
    }


def load_profiles() -> tuple[list[dict[str, Any]], str]:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    profiles: list[dict[str, Any]] = []
    active_id = ""
    if PROFILES_PATH.exists():
        try:
            raw = json.loads(PROFILES_PATH.read_text(encoding="utf-8"))
            active_id = str(raw.get("active_id") or "")
            for item in raw.get("profiles") or []:
                profile = _normalize(item)
                encrypted = str(item.get("password_protected") or "")
                profile["password"] = unprotect_secret(encrypted) if encrypted else ""
                if profile["host"].strip():
                    profiles.append(profile)
        except Exception:
            profiles = []
    if not profiles:
        legacy = _normalize(load_config()["profile"])
        if legacy["host"].strip():
            profiles = [legacy]
            active_id = legacy["id"]
            write_profiles(profiles, active_id)
        else:
            active_id = ""
            write_profiles([], "")
            return [], ""
    if not any(item["id"] == active_id for item in profiles):
        active_id = profiles[0]["id"]
    return profiles, active_id


def write_profiles(profiles: list[dict[str, Any]], active_id: str) -> None:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    payload_profiles = []
    for source in profiles:
        item = _normalize(source)
        password = item.pop("password", "")
        item["password_protected"] = protect_secret(password) if password else ""
        payload_profiles.append(item)
    temporary = PROFILES_PATH.with_suffix(".tmp")
    temporary.write_text(json.dumps({"active_id": active_id, "profiles": payload_profiles}, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(PROFILES_PATH)


def active_profile() -> dict[str, Any]:
    profiles, active_id = load_profiles()
    return next((item for item in profiles if item["id"] == active_id), profiles[0] if profiles else _normalize({}))


def save_and_activate(profile: dict[str, Any]) -> dict[str, Any]:
    profiles, _ = load_profiles()
    normalized = _normalize(profile)
    for index, current in enumerate(profiles):
        if current["id"] == normalized["id"] or (
            current["host"].lower() == normalized["host"].lower()
            and current["username"] == normalized["username"]
        ):
            if not profile.get("id"):
                normalized["id"] = current["id"]
            profiles[index] = normalized
            break
    else:
        profiles.append(normalized)
    write_profiles(profiles, normalized["id"])
    config = load_config()
    config["profile"] = dict(normalized)
    save_config(config)
    return normalized


def activate(profile_id: str) -> dict[str, Any]:
    profiles, _ = load_profiles()
    selected = next((item for item in profiles if item["id"] == profile_id), None)
    if selected is None:
        raise ValueError("Сервер не найден.")
    write_profiles(profiles, profile_id)
    config = load_config()
    config["profile"] = dict(selected)
    save_config(config)
    return selected


def delete(profile_id: str) -> dict[str, Any]:
    profiles, active_id = load_profiles()
    if len(profiles) <= 1:
        raise ValueError("Нельзя удалить единственный сервер.")
    profiles = [item for item in profiles if item["id"] != profile_id]
    if len(profiles) == 0:
        raise ValueError("Сервер не найден.")
    if active_id == profile_id:
        active_id = profiles[0]["id"]
    write_profiles(profiles, active_id)
    return activate(active_id)
