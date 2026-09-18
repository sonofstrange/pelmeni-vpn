"""
Pelmeni VPN — публичный реестр серверов.
Запускается как systemd-сервис на VPS.
"""
from __future__ import annotations

import hashlib
import json
import secrets
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Optional

from fastapi import FastAPI, HTTPException, Header, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

# ──────────────────────────────────────────────
DB_PATH = Path("/etc/pelmeni-api/servers.db")
ADMIN_TOKEN_PATH = Path("/etc/pelmeni-api/admin.token")
# ──────────────────────────────────────────────

app = FastAPI(title="Pelmeni VPN Registry", version="1.0.0", docs_url=None, redoc_url=None)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


# ── БД ────────────────────────────────────────

def _init_db() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(DB_PATH) as con:
        con.execute("""
            CREATE TABLE IF NOT EXISTS servers (
                pool_id         TEXT PRIMARY KEY,
                name            TEXT NOT NULL,
                location        TEXT NOT NULL DEFAULT '',
                host            TEXT NOT NULL,
                ssh_port        INTEGER NOT NULL DEFAULT 22,
                registrar_user  TEXT NOT NULL,
                registrar_password TEXT NOT NULL,
                host_key_type   TEXT NOT NULL DEFAULT '',
                host_key        TEXT NOT NULL DEFAULT '',
                fingerprint     TEXT NOT NULL DEFAULT '',
                days            INTEGER NOT NULL DEFAULT 30,
                daily_mb        INTEGER NOT NULL DEFAULT 0,
                monthly_mb      INTEGER NOT NULL DEFAULT 0,
                speed_mbps      INTEGER NOT NULL DEFAULT 0,
                max_users       INTEGER NOT NULL DEFAULT 50,
                tls             INTEGER NOT NULL DEFAULT 0,
                trust_level     TEXT NOT NULL DEFAULT 'COMMUNITY',
                update_token_hash TEXT NOT NULL,
                created_at      INTEGER NOT NULL,
                updated_at      INTEGER NOT NULL
            )
        """)
        con.execute("""
            CREATE TABLE IF NOT EXISTS migrations (
                old_host        TEXT PRIMARY KEY,
                new_host        TEXT NOT NULL,
                ssh_port        INTEGER NOT NULL DEFAULT 22,
                pool_id         TEXT NOT NULL DEFAULT '',
                created_at      INTEGER NOT NULL,
                updated_at      INTEGER NOT NULL
            )
        """)
        con.commit()


@contextmanager
def _db():
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    try:
        yield con
        con.commit()
    finally:
        con.close()


def _row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    d = dict(row)
    d["tls"] = bool(d["tls"])
    d["format"] = 1
    d.pop("update_token_hash", None)
    d.pop("created_at", None)
    d.pop("updated_at", None)
    return d


# ── Модели ────────────────────────────────────

class ServerRegister(BaseModel):
    pool_id: str = Field(min_length=4, max_length=64)
    name: str = Field(min_length=1, max_length=128)
    location: str = Field(default="", max_length=128)
    host: str = Field(min_length=1, max_length=253)
    ssh_port: int = Field(default=22, ge=1, le=65535)
    registrar_user: str = Field(min_length=1, max_length=64)
    registrar_password: str = Field(min_length=1, max_length=256)
    host_key_type: str = Field(default="", max_length=64)
    host_key: str = Field(default="", max_length=2048)
    fingerprint: str = Field(default="", max_length=128)
    days: int = Field(default=30, ge=0)
    daily_mb: int = Field(default=0, ge=0)
    monthly_mb: int = Field(default=0, ge=0)
    speed_mbps: int = Field(default=0, ge=0)
    max_users: int = Field(default=50, ge=1)
    tls: bool = False


class ServerUpdate(BaseModel):
    name: Optional[str] = Field(default=None, max_length=128)
    location: Optional[str] = Field(default=None, max_length=128)
    host: Optional[str] = Field(default=None, max_length=253)
    ssh_port: Optional[int] = Field(default=None, ge=1, le=65535)
    registrar_user: Optional[str] = Field(default=None, max_length=64)
    registrar_password: Optional[str] = Field(default=None, max_length=256)
    host_key_type: Optional[str] = Field(default=None, max_length=64)
    host_key: Optional[str] = Field(default=None, max_length=2048)
    fingerprint: Optional[str] = Field(default=None, max_length=128)
    days: Optional[int] = Field(default=None, ge=0)
    daily_mb: Optional[int] = Field(default=None, ge=0)
    monthly_mb: Optional[int] = Field(default=None, ge=0)
    speed_mbps: Optional[int] = Field(default=None, ge=0)
    max_users: Optional[int] = Field(default=None, ge=1)
    tls: Optional[bool] = None


class MigrationRegister(BaseModel):
    old_host: str = Field(min_length=1, max_length=253)
    new_host: str = Field(min_length=1, max_length=253)
    ssh_port: int = Field(default=22, ge=1, le=65535)
    pool_id: Optional[str] = Field(default="", max_length=64)


# ── Хелперы ───────────────────────────────────

def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _admin_token() -> str:
    if ADMIN_TOKEN_PATH.exists():
        return ADMIN_TOKEN_PATH.read_text().strip()
    token = secrets.token_urlsafe(32)
    ADMIN_TOKEN_PATH.parent.mkdir(parents=True, exist_ok=True)
    ADMIN_TOKEN_PATH.write_text(token)
    ADMIN_TOKEN_PATH.chmod(0o600)
    return token


def _require_token(authorization: Optional[str], pool_id: Optional[str] = None) -> None:
    """Проверяет update_token (для владельца сервера) или admin_token."""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Требуется авторизация.")
    token = authorization.removeprefix("Bearer ").strip()
    # Проверяем admin-токен
    if token == _admin_token():
        return
    # Проверяем update_token конкретного сервера
    if pool_id:
        with _db() as con:
            row = con.execute(
                "SELECT update_token_hash FROM servers WHERE pool_id = ?", (pool_id,)
            ).fetchone()
        if row and row["update_token_hash"] == _hash_token(token):
            return
    raise HTTPException(status_code=403, detail="Неверный токен.")


# ── Эндпоинты ─────────────────────────────────

@app.on_event("startup")
def startup():
    _init_db()
    # Гарантируем что admin-токен создан при первом запуске
    _admin_token()


@app.get("/api/v1/servers")
def list_servers() -> JSONResponse:
    """Список публичных серверов — этот URL использует приложение."""
    with _db() as con:
        rows = con.execute(
            "SELECT * FROM servers ORDER BY "
            "CASE trust_level WHEN 'OFFICIAL' THEN 0 WHEN 'VERIFIED' THEN 1 "
            "WHEN 'COMMUNITY' THEN 2 ELSE 3 END, name COLLATE NOCASE"
        ).fetchall()
    return JSONResponse([_row_to_dict(r) for r in rows])


@app.get("/api/v1/servers/{pool_id}")
def get_server(pool_id: str) -> JSONResponse:
    """Данные одного сервера — используется для автообновления IP у пользователей."""
    with _db() as con:
        row = con.execute(
            "SELECT * FROM servers WHERE pool_id = ?", (pool_id,)
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Сервер не найден.")
    return JSONResponse(_row_to_dict(row))


@app.post("/api/v1/servers", status_code=201)
def register_server(body: ServerRegister) -> dict:
    """Зарегистрировать новый публичный сервер. Возвращает update_token."""
    token = secrets.token_urlsafe(32)
    now = int(time.time())
    try:
        with _db() as con:
            con.execute("""
                INSERT INTO servers
                (pool_id, name, location, host, ssh_port, registrar_user, registrar_password,
                 host_key_type, host_key, fingerprint, days, daily_mb, monthly_mb,
                 speed_mbps, max_users, tls, trust_level, update_token_hash, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, (
                body.pool_id, body.name, body.location, body.host, body.ssh_port,
                body.registrar_user, body.registrar_password,
                body.host_key_type, body.host_key, body.fingerprint,
                body.days, body.daily_mb, body.monthly_mb, body.speed_mbps,
                body.max_users, int(body.tls), "COMMUNITY",
                _hash_token(token), now, now,
            ))
    except sqlite3.IntegrityError:
        raise HTTPException(status_code=409, detail=f"Сервер с pool_id '{body.pool_id}' уже существует.")
    return {"pool_id": body.pool_id, "update_token": token}


@app.put("/api/v1/servers/{pool_id}")
def update_server(pool_id: str, body: ServerUpdate, authorization: Optional[str] = Header(default=None)) -> dict:
    """Обновить данные сервера (IP, ключ и т.д.). Требует update_token."""
    _require_token(authorization, pool_id)
    fields = {k: v for k, v in body.model_dump().items() if v is not None}
    if not fields:
        raise HTTPException(status_code=400, detail="Нечего обновлять.")
    if "tls" in fields:
        fields["tls"] = int(fields["tls"])
    fields["updated_at"] = int(time.time())
    set_clause = ", ".join(f"{k} = ?" for k in fields)
    values = list(fields.values()) + [pool_id]
    with _db() as con:
        # Если меняется host — автоматически сохраняем перенос в таблицу migrations
        if body.host:
            old = con.execute("SELECT host, ssh_port FROM servers WHERE pool_id = ?", (pool_id,)).fetchone()
            if old and old["host"] != body.host:
                now_ts = int(time.time())
                port = body.ssh_port or old["ssh_port"]
                con.execute("""
                    INSERT INTO migrations (old_host, new_host, ssh_port, pool_id, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(old_host) DO UPDATE SET
                        new_host = excluded.new_host,
                        ssh_port = excluded.ssh_port,
                        pool_id = excluded.pool_id,
                        updated_at = excluded.updated_at
                """, (old["host"], body.host, port, pool_id, now_ts, now_ts))
        cur = con.execute(f"UPDATE servers SET {set_clause} WHERE pool_id = ?", values)
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="Сервер не найден.")
    return {"ok": True}


@app.get("/api/v1/migrations")
def list_migrations() -> JSONResponse:
    """Список всех переносов серверов — используется приложением для фонового обновления."""
    with _db() as con:
        rows = con.execute("SELECT old_host, new_host, ssh_port, pool_id, updated_at FROM migrations").fetchall()
    return JSONResponse([dict(r) for r in rows])


@app.get("/api/v1/migrations/{old_host}")
def get_migration(old_host: str) -> JSONResponse:
    """Проверить, перенесён ли конкретный IP/хост."""
    with _db() as con:
        row = con.execute(
            "SELECT old_host, new_host, ssh_port, pool_id, updated_at FROM migrations WHERE old_host = ?",
            (old_host.strip(),)
        ).fetchone()
    if row is None:
        return JSONResponse({"migrated": False, "old_host": old_host})
    d = dict(row)
    d["migrated"] = True
    return JSONResponse(d)


@app.post("/api/v1/migrations", status_code=201)
def register_migration(body: MigrationRegister, authorization: Optional[str] = Header(default=None)) -> dict:
    """Записать перенос сервера."""
    if authorization:
        _require_token(authorization, body.pool_id if body.pool_id else None)
    now_ts = int(time.time())
    with _db() as con:
        con.execute("""
            INSERT INTO migrations (old_host, new_host, ssh_port, pool_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(old_host) DO UPDATE SET
                new_host = excluded.new_host,
                ssh_port = excluded.ssh_port,
                pool_id = excluded.pool_id,
                updated_at = excluded.updated_at
        """, (body.old_host.strip(), body.new_host.strip(), body.ssh_port, body.pool_id or "", now_ts, now_ts))
        # Если такой сервер есть в servers по pool_id или old_host — обновим и там
        if body.pool_id:
            con.execute("UPDATE servers SET host = ?, ssh_port = ?, updated_at = ? WHERE pool_id = ?",
                        (body.new_host.strip(), body.ssh_port, now_ts, body.pool_id))
        else:
            con.execute("UPDATE servers SET host = ?, ssh_port = ?, updated_at = ? WHERE host = ?",
                        (body.new_host.strip(), body.ssh_port, now_ts, body.old_host.strip()))
    return {"ok": True, "old_host": body.old_host, "new_host": body.new_host}


@app.delete("/api/v1/migrations/{old_host}")
def delete_migration(old_host: str, authorization: Optional[str] = Header(default=None)) -> dict:
    """Удалить запись о переносе сервера."""
    if authorization:
        _require_token(authorization)
    with _db() as con:
        cur = con.execute("DELETE FROM migrations WHERE old_host = ?", (old_host.strip(),))
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="Запись о переносе не найдена.")
    return {"ok": True, "old_host": old_host}


@app.delete("/api/v1/servers/{pool_id}")
def delete_server(pool_id: str, authorization: Optional[str] = Header(default=None)) -> dict:
    """Убрать сервер из каталога."""
    _require_token(authorization, pool_id)
    with _db() as con:
        cur = con.execute("DELETE FROM servers WHERE pool_id = ?", (pool_id,))
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="Сервер не найден.")
    return {"ok": True}


@app.put("/api/v1/servers/{pool_id}/trust")
def set_trust(pool_id: str, body: dict, authorization: Optional[str] = Header(default=None)) -> dict:
    """Только admin: выставить trust_level (OFFICIAL / VERIFIED / COMMUNITY / SUSPICIOUS)."""
    _require_token(authorization)  # только admin
    level = str(body.get("trust_level", "")).upper()
    if level not in ("OFFICIAL", "VERIFIED", "COMMUNITY", "SUSPICIOUS"):
        raise HTTPException(status_code=400, detail="Неверный trust_level.")
    with _db() as con:
        cur = con.execute(
            "UPDATE servers SET trust_level = ?, updated_at = ? WHERE pool_id = ?",
            (level, int(time.time()), pool_id)
        )
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="Сервер не найден.")
    return {"ok": True}


@app.get("/health")
def health() -> dict:
    return {"ok": True}
