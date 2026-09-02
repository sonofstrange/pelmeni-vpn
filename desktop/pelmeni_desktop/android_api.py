from __future__ import annotations

import base64
import hashlib
import ipaddress
import json
import os
import re
import secrets
import socket
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Callable

import paramiko

from .storage import APP_DIR, KNOWN_HOSTS_PATH, protect_secret
from .version import APP_VERSION


GITHUB_API = "https://api.github.com/repos/sonofstrange/pelmeni-vpn"
PUBLIC_API = GITHUB_API + "/issues?state=open&labels=public-server&per_page=100"
PUBLIC_MARKER = "PELMENI_PUBLIC_V1:"
DEFAULT_WINDOW_KIB = 640
DEFAULT_PACKET_KIB = 32
DEFAULT_MTU = 8500


def _b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _fingerprint(key: paramiko.PKey) -> str:
    return "SHA256:" + base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip("=")


def ensure_remote_key(profile: dict[str, Any], prompt: Callable[[str, str, str], bool]) -> str:
    host = str(profile.get("host") or "").strip()
    port = int(profile.get("port", profile.get("ssh_port", 22)))
    if not host:
        raise ValueError("Сначала укажи сервер.")
    key = _remote_key(host, port)
    fingerprint = _fingerprint(key)
    lookup = host if port == 22 else f"[{host}]:{port}"
    known = paramiko.HostKeys()
    if KNOWN_HOSTS_PATH.exists():
        known.load(str(KNOWN_HOSTS_PATH))
    saved = known.lookup(lookup)
    pinned = saved.get(key.get_name()) if saved else None
    if pinned is not None and pinned.asbytes() == key.asbytes():
        return fingerprint
    if not prompt(lookup, key.get_name(), fingerprint):
        raise paramiko.SSHException("Новый SSH host key не подтверждён.")
    known._entries = [entry for entry in known._entries if lookup not in entry.hostnames]
    known.add(lookup, key.get_name(), key)
    KNOWN_HOSTS_PATH.parent.mkdir(parents=True, exist_ok=True)
    known.save(str(KNOWN_HOSTS_PATH))
    return fingerprint

def decode_access_code(raw: str) -> dict[str, Any]:
    value = raw.strip()
    if not value.startswith("PEL1-"):
        raise ValueError("Это не код доступа Пельмени VPN.")
    try:
        result = json.loads(_b64url_decode(value[5:]).decode("utf-8"))
    except Exception as error:
        raise ValueError("Код доступа повреждён.") from error
    if int(result.get("format", 0)) != 1:
        raise ValueError("Эта версия кода пока не поддерживается.")
    for key in ("host", "username", "password"):
        if not str(result.get(key, "")).strip():
            raise ValueError("Код доступа повреждён.")
    return result


def profile_from_access_code(raw: str) -> tuple[dict[str, Any], dict[str, Any]]:
    data = decode_access_code(raw)
    profile = {
        "id": str(uuid.uuid4()),
        "name": str(data.get("name") or data["host"]).strip(),
        "host": str(data["host"]).strip(),
        "port": int(data.get("ssh_port", 22)),
        "username": str(data["username"]).strip(),
        "password": str(data["password"]),
        "socks_port": int(data.get("socks_port", 1080)),
        "window_kib": int(data.get("window_kib", DEFAULT_WINDOW_KIB)),
        "packet_kib": int(data.get("packet_kib", DEFAULT_PACKET_KIB)),
        "mtu": int(data.get("mtu", DEFAULT_MTU)),
        "tls_enabled": bool(data.get("tls_enabled", False)),
        "tls_port": int(data.get("tls_port", 443)),
        "tls_ports": str(data.get("tls_ports", data.get("tls_port", 443))),
        "tls_host": str(data.get("host", "")).strip(),
    }
    policy = {
        key: data.get(key)
        for key in (
            "expires", "daily_mb", "monthly_mb", "speed_mbps",
            "issued_at", "tls_enabled", "tls_port", "tls_ports",
        )
        if key in data
    }
    return profile, policy


def safe_export(profile: dict[str, Any], settings: dict[str, Any]) -> Path:
    documents = Path(os.environ.get("USERPROFILE", str(Path.home()))) / "Documents"
    folder = documents / "Пельмени VPN"
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / "pelmeni-vpn-config.json"
    payload = {
        "format": 2,
        "type": "pelmeni_vpn_server",
        "name": profile.get("name", profile.get("host", "")),
        "host": profile.get("host", ""),
        "ssh_port": int(profile.get("port", 22)),
        "username": profile.get("username", "root"),
        "socks_port": int(profile.get("socks_port", 1080)),
        "vpn_mode": bool(settings.get("last_vpn_mode", False)),
        "telegram_proxy": bool(settings.get("last_proxy_mode", True)),
        "auto_reconnect": bool(settings.get("auto_reconnect", True)),
        "ssh_window_kib": int(profile.get("window_kib", DEFAULT_WINDOW_KIB)),
        "ssh_packet_kib": int(profile.get("packet_kib", DEFAULT_PACKET_KIB)),
        "vpn_mtu": int(profile.get("mtu", DEFAULT_MTU)),
        "requires_password": True,
    }
    forbidden = {"password", "password_protected", "secret", "token"}
    if forbidden.intersection(payload):
        raise ValueError("Экспорт содержит секретные поля.")
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return target


def import_safe_config(path: str) -> tuple[dict[str, Any], dict[str, Any]]:
    source = Path(path)
    if source.stat().st_size > 64 * 1024:
        raise ValueError("Файл конфигурации слишком большой.")
    data = json.loads(source.read_text(encoding="utf-8"))
    version = int(data.get("format", -1))
    if version not in (1, 2) or (version == 2 and data.get("type") != "pelmeni_vpn_server"):
        raise ValueError("Формат конфигурации не поддерживается.")
    host = str(data["host"]).strip()
    username = str(data["username"]).strip()
    password = str(data.get("password", "")) if version == 1 else ""
    if not host or not username:
        raise ValueError("Конфигурация повреждена.")
    profile = {
        "id": str(uuid.uuid4()),
        "name": str(data.get("name") or host).strip(),
        "host": host,
        "port": int(data["ssh_port"]),
        "username": username,
        "password": password,
        "socks_port": int(data["socks_port"]),
        "window_kib": int(data.get("ssh_window_kib", DEFAULT_WINDOW_KIB)),
        "packet_kib": int(data.get("ssh_packet_kib", DEFAULT_PACKET_KIB)),
        "mtu": int(data.get("vpn_mtu", DEFAULT_MTU)),
    }
    settings = {
        "last_vpn_mode": bool(data.get("vpn_mode", False)),
        "last_proxy_mode": bool(data.get("telegram_proxy", not data.get("vpn_mode", False))),
        "auto_reconnect": bool(data.get("auto_reconnect", True)),
    }
    return profile, settings


def _request_json(url: str, timeout: int = 20) -> Any:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "PelmeniVPN-Desktop/2",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"GitHub HTTP {response.status}")
        body = response.read(512 * 1024 + 1)
    if len(body) > 512 * 1024:
        raise RuntimeError("Ответ GitHub слишком большой.")
    return json.loads(body.decode("utf-8"))


def load_public_servers() -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for issue in _request_json(PUBLIC_API):
        body = str(issue.get("body") or "")
        marker = body.find(PUBLIC_MARKER)
        if marker < 0:
            continue
        encoded = body[marker + len(PUBLIC_MARKER):].split("-->", 1)[0].strip()
        try:
            entry = json.loads(_b64url_decode(encoded).decode("utf-8"))
            if int(entry.get("format", 0)) != 1:
                continue
            entry["issue_url"] = str(issue.get("html_url") or "")
            result.append(entry)
        except Exception:
            continue
    return result


def _remote_key(host: str, port: int, timeout: int = 15, preferred_type: str = "") -> paramiko.PKey:
    sock = socket.create_connection((host, port), timeout=timeout)
    transport = paramiko.Transport(sock)
    try:
        if preferred_type:
            options = transport.get_security_options()
            if preferred_type in options.key_types:
                options.key_types = (preferred_type,)
        transport.start_client(timeout=timeout)
        return transport.get_remote_server_key()
    finally:
        transport.close()


def claim_public_server(entry: dict[str, Any]) -> str:
    host, port = str(entry["host"]), int(entry.get("ssh_port", 22))
    key = _remote_key(host, port, preferred_type=str(entry.get("host_key_type") or ""))
    advertised = str(entry["fingerprint"])
    if _fingerprint(key) != advertised:
        raise RuntimeError("SSH-ключ сервера не совпал с каталогом.")
    encoded_key = base64.b64encode(key.asbytes()).decode("ascii")
    if key.get_name() != str(entry["host_key_type"]) or encoded_key != str(entry["host_key"]):
        raise RuntimeError("Запись публичного сервера повреждена.")
    client = paramiko.SSHClient()
    client.get_host_keys().add(host, key.get_name(), key)
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    try:
        client.connect(
            host,
            port=port,
            username=str(entry["registrar_user"]),
            password=str(entry["registrar_password"]),
            look_for_keys=False,
            allow_agent=False,
            timeout=20,
            auth_timeout=20,
        )
        _, stdout, stderr = client.exec_command("claim", timeout=60)
        output = stdout.read().decode("utf-8", "replace") + stderr.read().decode("utf-8", "replace")
        code = _marker(output, "PEL_PUBLIC_CODE=")
        if not code:
            raise RuntimeError(_marker(output, "PEL_PUBLIC_ERROR=") or "Сервер не выдал личный доступ.")
        return code
    finally:
        client.close()


def _version(value: str) -> tuple[int, int, int, int, int]:
    clean = re.sub(r"^[^0-9]*", "", value.strip())
    main, _, suffix = clean.partition("-")
    parts = [int(x or 0) for x in (re.findall(r"\d+", main) + ["0", "0", "0"])[:3]]
    prerelease = 1 if suffix else 0
    number = int((re.findall(r"\d+", suffix) or [0])[-1])
    return parts[0], parts[1], parts[2], -prerelease, number


def check_updates(include_prereleases: bool = False) -> dict[str, Any] | None:
    raw = _request_json(GITHUB_API + ("/releases?per_page=20" if include_prereleases else "/releases/latest"))
    releases = raw if isinstance(raw, list) else [raw]
    releases = [r for r in releases if not r.get("draft") and r.get("tag_name")]
    if not releases:
        return None
    release = max(releases, key=lambda item: _version(str(item["tag_name"])))
    if _version(str(release["tag_name"])) <= _version(APP_VERSION):
        return None
    assets = release.get("assets") or []
    desktop = next((a for a in assets if str(a.get("name", "")).lower().endswith(".exe")), None)
    return {
        "version": str(release["tag_name"]),
        "notes": str(release.get("body") or "").strip(),
        "page_url": str(release.get("html_url") or ""),
        "download_url": str((desktop or {}).get("browser_download_url") or release.get("html_url") or ""),
        "sha256": str((desktop or {}).get("digest") or "").removeprefix("sha256:"),
        "size": int((desktop or {}).get("size") or 0),
        "prerelease": bool(release.get("prerelease")),
    }


def _load_scripts() -> dict[str, str]:
    return json.loads((Path(__file__).with_name("android_scripts.json")).read_text(encoding="utf-8"))


def _marker(output: str, prefix: str) -> str:
    for line in output.splitlines():
        if line.startswith(prefix):
            return line[len(prefix):].strip()
    return ""


class ServerPeopleApi:
    def __init__(self, profile: dict[str, Any]):
        self.profile = profile
        self.scripts = _load_scripts()

    def _client(self) -> paramiko.SSHClient:
        if not KNOWN_HOSTS_PATH.exists():
            raise RuntimeError("Сначала подключись и подтверди SSH host key сервера.")
        client = paramiko.SSHClient()
        client.load_host_keys(str(KNOWN_HOSTS_PATH))
        client.set_missing_host_key_policy(paramiko.RejectPolicy())
        client.connect(
            str(self.profile["host"]),
            port=int(self.profile.get("port", 22)),
            username=str(self.profile["username"]),
            password=str(self.profile["password"]),
            look_for_keys=False,
            allow_agent=False,
            timeout=20,
            banner_timeout=20,
            auth_timeout=20,
            compress=True,
        )
        return client

    @staticmethod
    def _execute(client: paramiko.SSHClient, command: str, content: str = "", timeout: int = 180) -> tuple[int, str]:
        channel = client.get_transport().open_session(timeout=10)
        channel.settimeout(1.0)
        channel.exec_command(command)
        if content:
            channel.sendall(content.encode("utf-8"))
        channel.shutdown_write()
        chunks: list[bytes] = []
        errors: list[bytes] = []
        deadline = time.monotonic() + timeout
        while not channel.exit_status_ready() or channel.recv_ready() or channel.recv_stderr_ready():
            if channel.recv_ready():
                chunks.append(channel.recv(65536))
            if channel.recv_stderr_ready():
                errors.append(channel.recv_stderr(65536))
            if time.monotonic() >= deadline:
                channel.close()
                raise TimeoutError("Сервер слишком долго отвечает.")
            time.sleep(0.05)
        code = channel.recv_exit_status()
        channel.close()
        return code, (b"".join(chunks) + b"".join(errors)).decode("utf-8", "replace")

    def _code_profile(self, use_tls: bool = False) -> dict[str, Any]:
        return {
            "name": self.profile.get("name", self.profile["host"]),
            "host": self.profile["host"],
            "ssh_port": int(self.profile.get("port", 22)),
            "socks_port": str(self.profile.get("socks_port", 1080)),
            "window_kib": int(self.profile.get("window_kib", DEFAULT_WINDOW_KIB)),
            "packet_kib": int(self.profile.get("packet_kib", DEFAULT_PACKET_KIB)),
            "mtu": int(self.profile.get("mtu", DEFAULT_MTU)),
            "tls_enabled": bool(use_tls),
        }

    def _management_script(self, action: str, request: dict[str, Any] | None) -> str:
        payload = "" if request is None else base64.b64encode(json.dumps(request, ensure_ascii=False, separators=(",", ":")).encode()).decode()
        worker = base64.b64encode(self.scripts["worker"].encode()).decode()
        policy = base64.b64encode(self.scripts["policy"].encode()).decode()
        return (
            "set -Eeuo pipefail\nexport DEBIAN_FRONTEND=noninteractive\n"
            "if ! command -v python3 >/dev/null || ! command -v nft >/dev/null; then\n"
            "  if ! command -v apt-get >/dev/null; then echo 'PELMENI_ERROR=Поддерживаются Debian и Ubuntu.'; exit 40; fi\n"
            "  apt-get update -qq && apt-get install -y -qq python3 nftables >/dev/null\nfi\n"
            "install -d -m 0700 /etc/pelmeni-vpn\n"
            "[ -f /etc/pelmeni-vpn/users.json ] || printf '[]' > /etc/pelmeni-vpn/users.json\n"
            "groupadd -f pelmeni-vpn\ninstall -d -m 0755 /etc/ssh/sshd_config.d\n"
            "cat > /etc/ssh/sshd_config.d/90-pelmeni-users.conf <<'PELSSH'\n"
            "Match Group pelmeni-vpn\n    AllowTcpForwarding yes\n    X11Forwarding no\n"
            "    AllowAgentForwarding no\n    PermitTunnel no\n    ForceCommand internal-sftp\nMatch all\nPELSSH\n"
            "sshd -t\n(systemctl reload ssh || systemctl reload sshd) >/dev/null 2>&1 || true\n"
            f"printf '%s' '{policy}' | base64 -d > /usr/local/sbin/pelmeni-user-policy\n"
            "chmod 0700 /usr/local/sbin/pelmeni-user-policy\n"
            "cat > /etc/systemd/system/pelmeni-user-policy.service <<'PELUNIT'\n"
            "[Unit]\nDescription=Pelmeni VPN user limits\nAfter=network.target\n[Service]\nType=simple\n"
            "ExecStart=/usr/local/sbin/pelmeni-user-policy\nRestart=always\nRestartSec=3\nNice=10\nCPUWeight=10\n"
            "[Install]\nWantedBy=multi-user.target\nPELUNIT\n"
            f"printf '%s' '{worker}' | base64 -d > /tmp/pelmeni-users.py\n"
            f"python3 /tmp/pelmeni-users.py '{action}' '{payload}'\n"
        )

    def run(self, action: str, request: dict[str, Any] | None = None) -> Any:
        client = self._client()
        try:
            _, uid = self._execute(client, "id -u", timeout=15)
            root = uid.strip() == "0"
            if not root:
                code, _ = self._execute(client, "sudo -S -p '' -v", str(self.profile["password"]) + "\n", 30)
                if code != 0:
                    raise PermissionError("Для управления людьми нужны права root или sudo.")
            code, output = self._execute(
                client,
                "bash -s" if root else "sudo -n bash -s",
                self._management_script(action, request),
                180,
            )
            marker = _marker(output, "PELMENI_USERS=")
            if code != 0 or not marker:
                raise RuntimeError(_marker(output, "PELMENI_ERROR=") or "Сервер не применил настройки пользователей.")
            return json.loads(base64.b64decode(marker).decode("utf-8"))
        finally:
            client.close()

    def list(self) -> list[dict[str, Any]]:
        return list(self.run("list", {"code_profile": self._code_profile(False)}))

    def create(self, label: str, login: str, days: int, daily_mb: int, monthly_mb: int, speed_mbps: int) -> dict[str, Any]:
        normalized = re.sub(r"_+", "_", re.sub(r"[^a-z0-9_-]", "_", login.lower()))
        if normalized.startswith("-"):
            normalized = "_" + normalized[1:]
        if not normalized:
            raise ValueError("Укажи логин латиницей.")
        if not normalized.startswith("pel_"):
            normalized = "pel_" + normalized
        users = self.run("create", {
            "label": label.strip(), "login": normalized[:28], "days": int(days),
            "daily_mb": int(daily_mb), "monthly_mb": int(monthly_mb),
            "speed_mbps": int(speed_mbps), "use_tls": False,
            "code_profile": self._code_profile(False),
        })
        return next(user for user in users if user.get("login") == normalized[:28])

    def extend(self, login: str, days: int) -> Any:
        return self.run("extend", {"login": login, "days": int(days)})

    def revoke(self, login: str) -> Any:
        return self.run("revoke", {"login": login})

    def update_limits(self, login: str, daily_mb: int, monthly_mb: int, speed_mbps: int) -> Any:
        return self.run("limits", {"login": login, "daily_mb": int(daily_mb), "monthly_mb": int(monthly_mb), "speed_mbps": int(speed_mbps)})

    def reset_usage(self, login: str) -> Any:
        return self.run("reset", {"login": login})

    def _save_tls_bundle(self, bundle: bytes, password: str) -> Path:
        if not bundle or not password:
            raise RuntimeError("Сервер не выдал TLS-сертификат.")
        folder = APP_DIR / "tls"
        folder.mkdir(parents=True, exist_ok=True)
        target = folder / (str(self.profile.get("id") or "active") + ".json")
        target.write_text(json.dumps({
            "pkcs12": protect_secret(base64.b64encode(bundle).decode()),
            "password": protect_secret(password),
        }), encoding="utf-8")
        return target

    def configure_tls(self) -> Path:
        host = str(self.profile["host"]).strip()
        try:
            san = "IP:" + str(ipaddress.ip_address(host))
        except ValueError:
            if not re.fullmatch(r"[A-Za-z0-9.-]+", host):
                raise ValueError("Некорректный домен сервера.")
            san = "DNS:" + host
        ssh_port = int(self.profile.get("port", 22))
        script = f'''set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive
if [ ! -f /etc/debian_version ] || ! command -v apt-get >/dev/null; then echo 'PELmeni_ERROR=UNSUPPORTED_OS'; exit 40; fi
if [ ! -f /etc/stunnel/pelmeni.conf ] && command -v ss >/dev/null && ss -ltnH | awk '{{print $4}}' | grep -Eq '(^|\\]):443$|:443$'; then echo 'PELmeni_ERROR=PORT_443_BUSY'; exit 43; fi
apt-get update -qq
apt-get install -y -qq stunnel4 openssl >/dev/null
install -d -m 0700 /etc/stunnel/pelmeni
cd /etc/stunnel/pelmeni
if [ ! -s ca.key ] || [ ! -s client.key ] || [ ! -s server.key ]; then
 rm -f ca.* server.* client.* client.p12 p12.password
 openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes -keyout ca.key -out ca.crt -subj '/CN=Pelmeni VPN private CA' >/dev/null 2>&1
 openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj '/CN=Pelmeni VPN server' >/dev/null 2>&1
 printf '%s\n' 'basicConstraints=CA:FALSE' 'keyUsage=digitalSignature,keyEncipherment' 'extendedKeyUsage=serverAuth' 'subjectAltName={san}' > server.ext
 openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt -days 3650 -sha256 -extfile server.ext >/dev/null 2>&1
 openssl req -newkey rsa:2048 -nodes -keyout client.key -out client.csr -subj '/CN=Pelmeni VPN client' >/dev/null 2>&1
 printf '%s\n' 'basicConstraints=CA:FALSE' 'keyUsage=digitalSignature' 'extendedKeyUsage=clientAuth' > client.ext
 openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out client.crt -days 3650 -sha256 -extfile client.ext >/dev/null 2>&1
 openssl rand -hex 24 > p12.password
fi
openssl pkcs12 -export -out client.p12 -inkey client.key -in client.crt -certfile ca.crt -passout file:p12.password >/dev/null 2>&1
cat > /etc/stunnel/pelmeni.conf <<'PELCONF'
foreground = yes
debug = notice
[pelmeni]
accept = 0.0.0.0:443
connect = 127.0.0.1:{ssh_port}
cert = /etc/stunnel/pelmeni/server.crt
key = /etc/stunnel/pelmeni/server.key
CAfile = /etc/stunnel/pelmeni/ca.crt
verify = 2
sslVersionMin = TLSv1.2
TIMEOUTclose = 0
socket = l:TCP_NODELAY=1
socket = r:TCP_NODELAY=1
PELCONF
id -u pelmeni-stunnel >/dev/null 2>&1 || useradd --system --home /nonexistent --shell /usr/sbin/nologin pelmeni-stunnel
chown -R pelmeni-stunnel:pelmeni-stunnel /etc/stunnel/pelmeni
chmod 0600 /etc/stunnel/pelmeni/*.key /etc/stunnel/pelmeni/client.p12 /etc/stunnel/pelmeni/p12.password
cat > /etc/systemd/system/pelmeni-stunnel.service <<'PELUNIT'
[Unit]
Description=Pelmeni VPN mutual TLS wrapper
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
User=pelmeni-stunnel
ExecStart=/usr/bin/stunnel4 /etc/stunnel/pelmeni.conf
Restart=on-failure
RestartSec=2
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
[Install]
WantedBy=multi-user.target
PELUNIT
systemctl daemon-reload
systemctl enable --now pelmeni-stunnel.service >/dev/null
sleep 1
systemctl is-active --quiet pelmeni-stunnel.service || {{ echo 'PELmeni_ERROR=SERVICE_FAILED'; exit 44; }}
if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then ufw allow 443/tcp comment 'Pelmeni VPN TLS' >/dev/null || true; fi
printf 'PELmeni_P12='; base64 -w0 client.p12; printf '\n'
printf 'PELmeni_PASSWORD='; cat p12.password; printf '\n'
echo 'PELmeni_OK=1'
'''
        client = self._client()
        try:
            _, uid = self._execute(client, "id -u", timeout=15)
            root = uid.strip() == "0"
            if not root:
                code, _ = self._execute(client, "sudo -S -p '' -v", str(self.profile["password"]) + "\n", 30)
                if code != 0:
                    raise PermissionError("Пользователю нужны права sudo с этим же паролем.")
            code, output = self._execute(client, "bash -s" if root else "sudo -n bash -s", script, 240)
            if code != 0 or "PELmeni_OK=1" not in output:
                reason = _marker(output, "PELmeni_ERROR=")
                messages = {"UNSUPPORTED_OS": "Автонастройка поддерживает Debian и Ubuntu.", "PORT_443_BUSY": "Порт 443 уже занят другим сервисом. Сервер не изменён.", "SERVICE_FAILED": "stunnel установлен, но сервис не запустился."}
                raise RuntimeError(messages.get(reason, "Не удалось автоматически настроить TLS на сервере."))
            return self._save_tls_bundle(base64.b64decode(_marker(output, "PELmeni_P12=")), _marker(output, "PELmeni_PASSWORD="))
        finally:
            client.close()

    def remove_tls(self) -> None:
        script = """set -Eeuo pipefail
systemctl disable --now pelmeni-stunnel.service >/dev/null 2>&1 || true
rm -f /etc/systemd/system/pelmeni-stunnel.service /etc/stunnel/pelmeni.conf
rm -rf /etc/stunnel/pelmeni
systemctl daemon-reload
systemctl reset-failed pelmeni-stunnel.service >/dev/null 2>&1 || true
if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then ufw --force delete allow 443/tcp >/dev/null 2>&1 || true; ufw --force delete allow 8443/tcp >/dev/null 2>&1 || true; fi
id -u pelmeni-stunnel >/dev/null 2>&1 && userdel pelmeni-stunnel >/dev/null 2>&1 || true
echo 'PELmeni_TLS_REMOVED=1'
"""
        client = self._client()
        try:
            _, uid = self._execute(client, "id -u", timeout=15)
            root = uid.strip() == "0"
            if not root:
                code, _ = self._execute(client, "sudo -S -p '' -v", str(self.profile["password"]) + "\n", 30)
                if code != 0:
                    raise PermissionError("Пользователю нужны права sudo с этим же паролем.")
            code, output = self._execute(client, "bash -s" if root else "sudo -n bash -s", script, 60)
            if code != 0 or "PELmeni_TLS_REMOVED=1" not in output:
                raise RuntimeError("Не удалось полностью удалить TLS с сервера.")
            target = APP_DIR / "tls" / (str(self.profile.get("id") or "active") + ".json")
            target.unlink(missing_ok=True)
        finally:
            client.close()
    def fetch_tls(self) -> Path:
        client = self._client()
        try:
            sftp = client.open_sftp()
            bundle = sftp.file(".pelmeni-tls.p12", "rb").read()
            password = sftp.file(".pelmeni-tls-password", "rb").read().decode().strip()
            return self._save_tls_bundle(bundle, password)
        finally:
            client.close()


def prepare_public_server(
    profile: dict[str, Any], name: str, location: str, days: int,
    daily_mb: int, monthly_mb: int, speed_mbps: int, max_users: int,
    use_tls: bool = False,
) -> dict[str, Any]:
    """Configure the same isolated public registrar used by the Android client."""
    api = ServerPeopleApi(profile)
    api.list()  # Install/refresh the shared per-user policy controller first.
    if not KNOWN_HOSTS_PATH.exists():
        raise RuntimeError("Сначала подключись и подтверди SSH-ключ сервера.")
    host_keys = paramiko.HostKeys(str(KNOWN_HOSTS_PATH))
    known = host_keys.lookup(str(profile["host"]))
    if not known:
        raise RuntimeError("Сначала подключись и подтверди SSH-ключ сервера.")
    key = next(iter(known.values()))
    request = {
        "name": name.strip() or str(profile.get("name") or profile["host"]),
        "location": location.strip(), "host": profile["host"],
        "ssh_port": int(profile.get("port", 22)),
        "socks_port": str(profile.get("socks_port", 1080)),
        "window_kib": int(profile.get("window_kib", DEFAULT_WINDOW_KIB)),
        "packet_kib": int(profile.get("packet_kib", DEFAULT_PACKET_KIB)),
        "mtu": int(profile.get("mtu", DEFAULT_MTU)),
        "days": int(days), "daily_mb": int(daily_mb),
        "monthly_mb": int(monthly_mb), "speed_mbps": int(speed_mbps),
        "max_users": int(max_users), "tls": bool(use_tls),
        "host_key_type": key.get_name(),
        "host_key": base64.b64encode(key.asbytes()).decode("ascii"),
        "fingerprint": _fingerprint(key),
    }
    payload = base64.b64encode(json.dumps(request, ensure_ascii=False, separators=(",", ":")).encode()).decode()
    claim = base64.b64encode(api.scripts["public_claim"].encode()).decode()
    script = (
        "set -Eeuo pipefail\nexport DEBIAN_FRONTEND=noninteractive\n"
        "if ! command -v python3 >/dev/null; then apt-get update -qq && apt-get install -y -qq python3; fi\n"
        "install -d -m 0700 /etc/pelmeni-vpn/public-pools\n"
        f"printf '%s' '{claim}' | base64 -d > /usr/local/sbin/pelmeni-public-claim\n"
        "chmod 0700 /usr/local/sbin/pelmeni-public-claim\n"
        f"python3 - '{payload}' <<'PY'\n{api.scripts['public_installer']}\nPY\n"
    )
    client = api._client()
    try:
        _, uid = api._execute(client, "id -u", timeout=15)
        root = uid.strip() == "0"
        if not root:
            code, _ = api._execute(client, "sudo -S -p '' -v", str(profile["password"]) + "\n", 30)
            if code != 0:
                raise PermissionError("Для публичного режима нужны права root или sudo.")
        code, output = api._execute(client, "bash -s" if root else "sudo -n bash -s", script, 180)
        marker = _marker(output, "PELMENI_PUBLIC=")
        if code != 0 or not marker:
            raise RuntimeError(_marker(output, "PELMENI_ERROR=") or "Сервер не создал публичный доступ.")
        entry = json.loads(base64.b64decode(marker).decode("utf-8"))
    finally:
        client.close()
    encoded = base64.urlsafe_b64encode(json.dumps(entry, ensure_ascii=False, separators=(",", ":")).encode()).decode().rstrip("=")
    limits = (
        f"день: {entry.get('daily_mb') or '∞'} МБ · месяц: {entry.get('monthly_mb') or '∞'} МБ · "
        f"скорость: {entry.get('speed_mbps') or '∞'} Мбит/с"
    )
    body = (
        f"<!-- {PUBLIC_MARKER}{encoded} -->\n\n### Бесплатный сервер Пельмени VPN\n\n"
        f"- Название: {entry['name']}\n- Регион: {entry.get('location') or 'не указан'}\n"
        f"- Лимиты: {limits}\n- TLS: {'да' if entry.get('tls') else 'нет'}\n\n"
        "Не редактируйте скрытый служебный маркер выше. Чтобы убрать сервер из каталога, закройте Issue."
    )
    entry["publish_url"] = "https://github.com/sonofstrange/pelmeni-vpn/issues/new?" + urllib.parse.urlencode({
        "template": "public-server.md", "title": "[PUBLIC] " + str(entry["name"]), "body": body,
    })
    return entry


def speed_test(socks_port: int, use_system_proxy: bool, download_bytes: int = 4 * 1024 * 1024, upload_bytes: int = 1024 * 1024) -> dict[str, int]:
    try:
        import requests
    except ImportError as error:
        raise RuntimeError("Для теста скорости нужен пакет requests[socks].") from error
    proxies = None if use_system_proxy else {
        "http": f"socks5h://127.0.0.1:{socks_port}",
        "https": f"socks5h://127.0.0.1:{socks_port}",
    }
    session = requests.Session()
    session.headers.update({"User-Agent": "PelmeniVPN-SpeedTest/1", "Accept-Encoding": "identity"})
    latencies = []
    for _ in range(3):
        started = time.perf_counter()
        response = session.get("https://speed.cloudflare.com/__down?bytes=0", proxies=proxies, timeout=20)
        response.raise_for_status()
        latencies.append(max(1, round((time.perf_counter() - started) * 1000)))
    started = time.perf_counter()
    response = session.get(f"https://speed.cloudflare.com/__down?bytes={download_bytes}", proxies=proxies, timeout=30)
    response.raise_for_status()
    downloaded = len(response.content)
    download_rate = round(downloaded / max(0.001, time.perf_counter() - started))
    payload = secrets.token_bytes(upload_bytes)
    started = time.perf_counter()
    response = session.post("https://speed.cloudflare.com/__up", data=payload, proxies=proxies, timeout=30, headers={"Content-Type": "application/octet-stream"})
    response.raise_for_status()
    upload_rate = round(upload_bytes / max(0.001, time.perf_counter() - started))
    return {"latency_ms": sorted(latencies)[1], "download_bps": download_rate, "upload_bps": upload_rate}
