from __future__ import annotations

import base64
import json
import secrets
import time
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)

from .storage import APP_DIR


STATE_PATH = APP_DIR / "developer-mode.json"
_PUBLIC_KEY = Ed25519PublicKey.from_public_bytes(
    base64.b64decode("oYSVAPYso7x/GGca6QkFdASzvpQury3w3m38ct/9cEc=")
)


def _canonical(payload: dict[str, object]) -> bytes:
    return json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def is_developer_mode() -> bool:
    try:
        if not STATE_PATH.is_file() or STATE_PATH.stat().st_size > 16 * 1024:
            return False
        token = json.loads(STATE_PATH.read_text(encoding="utf-8"))
        payload = token.get("payload")
        if not isinstance(payload, dict) or payload.get("version") != 1:
            return False
        signature = base64.b64decode(str(token.get("signature") or ""), validate=True)
        _PUBLIC_KEY.verify(signature, _canonical(payload))
        return payload.get("enabled") is True
    except (OSError, ValueError, TypeError, InvalidSignature, json.JSONDecodeError):
        return False


def state_revision() -> int:
    try:
        return STATE_PATH.stat().st_mtime_ns
    except OSError:
        return 0


def write_signed_state(enabled: bool, private_key_b64: str) -> None:
    private_key = Ed25519PrivateKey.from_private_bytes(
        base64.b64decode(private_key_b64, validate=True)
    )
    payload: dict[str, object] = {
        "version": 1,
        "enabled": bool(enabled),
        "issued_at": int(time.time()),
        "nonce": secrets.token_hex(16),
    }
    token = {
        "payload": payload,
        "signature": base64.b64encode(
            private_key.sign(_canonical(payload))
        ).decode("ascii"),
    }
    APP_DIR.mkdir(parents=True, exist_ok=True)
    temporary = STATE_PATH.with_suffix(".tmp")
    temporary.write_text(
        json.dumps(token, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    temporary.replace(STATE_PATH)
