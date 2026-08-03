from __future__ import annotations

import base64
import hashlib
import socket
import ssl
import struct
import subprocess
import tempfile
import time
import json
import os
from pathlib import Path
from typing import Any, Callable

import paramiko
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.serialization import pkcs12

from .socks_vpn import SocksProxy
from .storage import APP_DIR, KNOWN_HOSTS_PATH, unprotect_secret


HostKeyPrompt = Callable[[str, str, str], bool]


def _fingerprint(key: paramiko.PKey) -> str:
    digest = hashlib.sha256(key.asbytes()).digest()
    return "SHA256:" + base64.b64encode(digest).decode("ascii").rstrip("=")


def _physical_ipv4_source() -> tuple[int, str] | None:
    if os.name != "nt":
        return None
    script = (
        "$routes=Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop | "
        "ForEach-Object {$r=$_;$a=Get-NetAdapter -InterfaceIndex $r.InterfaceIndex -IncludeHidden -ErrorAction SilentlyContinue;"
        "if($a -and $a.Status -eq 'Up' -and $a.HardwareInterface){[PSCustomObject]@{Index=$r.InterfaceIndex;Metric=$r.RouteMetric+$r.InterfaceMetric}}} | "
        "Sort-Object Metric; $route=$routes | Select-Object -First 1; if($null -eq $route){exit 2}; "
        "$ip=Get-NetIPAddress -InterfaceIndex $route.Index -AddressFamily IPv4 -AddressState Preferred -ErrorAction Stop | "
        "Where-Object {$_.IPAddress -notlike '169.254.*'} | Select-Object -First 1; if($null -eq $ip){exit 3}; "
        "Write-Output ($route.Index.ToString()+'|'+$ip.IPAddress)"
    )
    try:
        result = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=5,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        if result.returncode:
            return None
        index, address = result.stdout.strip().split("|", 1)
        socket.inet_aton(address)
        return int(index), address
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def _connect_socket(host: str, port: int, timeout: float = 8) -> socket.socket:
    physical = _physical_ipv4_source()
    last_error: Exception | None = None
    for family, sock_type, protocol, _, target in socket.getaddrinfo(
        host, int(port), socket.AF_INET, socket.SOCK_STREAM
    ):
        raw = socket.socket(family, sock_type, protocol)
        try:
            raw.settimeout(timeout)
            raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            if physical is not None:
                interface_index, source_address = physical
                raw.bind((source_address, 0))
                raw.setsockopt(
                    socket.IPPROTO_IP,
                    getattr(socket, "IP_UNICAST_IF", 31),
                    struct.pack("!I", interface_index),
                )
            raw.connect(target)
            return raw
        except Exception as error:
            last_error = error
            raw.close()
    raise OSError(f"Не удалось подключиться к {host}:{port}: {last_error}")

class _ConfirmHostKeyPolicy(paramiko.MissingHostKeyPolicy):
    def __init__(self, prompt: HostKeyPrompt, known_hosts: Path):
        self.prompt = prompt
        self.known_hosts = known_hosts

    def missing_host_key(
        self,
        client: paramiko.SSHClient,
        hostname: str,
        key: paramiko.PKey,
    ) -> None:
        fingerprint = _fingerprint(key)
        if not self.prompt(hostname, key.get_name(), fingerprint):
            raise paramiko.SSHException("Новый SSH host key не подтверждён")
        client.get_host_keys().add(hostname, key.get_name(), key)
        self.known_hosts.parent.mkdir(parents=True, exist_ok=True)
        client.save_host_keys(str(self.known_hosts))


def _tls_socket(profile: dict[str, Any]) -> ssl.SSLSocket:
    profile_id = str(profile.get("id") or "active")
    bundle_path = APP_DIR / "tls" / (profile_id + ".json")
    if not bundle_path.exists():
        raise RuntimeError("TLS включён, но клиентский сертификат не установлен. Открой параметры сервера → TLS-обёртка.")
    protected = json.loads(bundle_path.read_text(encoding="utf-8"))
    bundle = base64.b64decode(unprotect_secret(str(protected["pkcs12"])))
    password = unprotect_secret(str(protected["password"])).encode()
    key, certificate, chain = pkcs12.load_key_and_certificates(bundle, password)
    if key is None or certificate is None:
        raise RuntimeError("Клиентский TLS-сертификат повреждён.")
    cert_pem = certificate.public_bytes(serialization.Encoding.PEM)
    ca_pem = b"".join(item.public_bytes(serialization.Encoding.PEM) for item in (chain or []))
    key_pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    cert_name = key_name = ""
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=".pem") as cert_file:
            cert_name = cert_file.name
            cert_file.write(cert_pem)
        with tempfile.NamedTemporaryFile(delete=False, suffix=".key") as key_file:
            key_name = key_file.name
            key_file.write(key_pem)
        context = ssl.create_default_context(ssl.Purpose.SERVER_AUTH)
        if ca_pem:
            context.load_verify_locations(cadata=ca_pem.decode("ascii"))
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(cert_name, key_name)
    finally:
        for temporary in (cert_name, key_name):
            if temporary:
                try:
                    os.unlink(temporary)
                except OSError:
                    pass
    host = str(profile["host"]).strip()
    ports = [int(value.strip()) for value in str(profile.get("tls_ports") or profile.get("tls_port", 443)).split(",") if value.strip()]
    last_error: Exception | None = None
    for port in ports or [443]:
        raw: socket.socket | None = None
        try:
            raw = _connect_socket(host, port, timeout=8)
            return context.wrap_socket(raw, server_hostname=host)
        except Exception as error:
            last_error = error
            if raw is not None:
                raw.close()
    raise RuntimeError("Не удалось установить TLS-соединение: " + str(last_error))

class TunnelManager:
    def __init__(self) -> None:
        self.client: paramiko.SSHClient | None = None
        self.proxy: SocksProxy | None = None
        self.telegram_proxy: SocksProxy | None = None
        self.connected_at = 0.0
        self.latency_ms = -1

    def start(
        self,
        profile: dict[str, Any],
        prompt: HostKeyPrompt,
        enable_telegram_proxy: bool = False,
    ) -> None:
        self.stop()
        host = str(profile["host"]).strip()
        port = int(profile["port"])
        username = str(profile["username"]).strip()
        password = str(profile["password"])
        telegram_port = int(profile["socks_port"])
        started = time.perf_counter()
        connection_socket = _tls_socket(profile) if profile.get("tls_enabled") else _connect_socket(host, port)
        self.latency_ms = max(1, round((time.perf_counter() - started) * 1000))

        APP_DIR.mkdir(parents=True, exist_ok=True)
        client = paramiko.SSHClient()
        if KNOWN_HOSTS_PATH.exists():
            client.load_host_keys(str(KNOWN_HOSTS_PATH))
        client.set_missing_host_key_policy(
            _ConfirmHostKeyPolicy(prompt, KNOWN_HOSTS_PATH)
        )
        private_proxy: SocksProxy | None = None
        telegram_proxy: SocksProxy | None = None
        try:
            client.connect(
                hostname=host,
                port=port,
                username=username,
                password=password,
                timeout=15,
                banner_timeout=15,
                auth_timeout=15,
                look_for_keys=False,
                allow_agent=False,
                compress=True,
                sock=connection_socket,
            )
            transport = client.get_transport()
            if transport is None or not transport.is_active():
                raise ConnectionError("SSH-транспорт не запущен")
            transport.set_keepalive(20)
            # VPN always uses an ephemeral private listener. Telegram's stable
            # port is opened only when the separate Proxy mode is enabled.
            private_proxy = SocksProxy(transport, 0)
            private_proxy.start()
            if enable_telegram_proxy:
                telegram_proxy = SocksProxy(transport, telegram_port)
                telegram_proxy.start()
        except Exception:
            if telegram_proxy is not None:
                try:
                    telegram_proxy.stop()
                except Exception:
                    pass
            if private_proxy is not None:
                try:
                    private_proxy.stop()
                except Exception:
                    pass
            client.close()
            try:
                connection_socket.close()
            except Exception:
                pass
            raise
        self.client = client
        self.proxy = private_proxy
        self.telegram_proxy = telegram_proxy
        self.connected_at = time.monotonic()

    @property
    def vpn_socks_port(self) -> int:
        if self.proxy is None:
            raise ConnectionError("Внутренний SOCKS для VPN не запущен")
        return self.proxy.port

    def is_telegram_proxy_active(self) -> bool:
        return self.telegram_proxy is not None and self.is_active()

    def enable_telegram_proxy(self, port: int) -> None:
        if self.telegram_proxy is not None:
            return
        transport = self.client.get_transport() if self.client else None
        if transport is None or not transport.is_active():
            raise ConnectionError("SSH-транспорт не подключён")
        proxy = SocksProxy(transport, int(port))
        try:
            proxy.start()
        except Exception:
            try:
                proxy.stop()
            except Exception:
                pass
            raise
        self.telegram_proxy = proxy

    def disable_telegram_proxy(self) -> None:
        proxy, self.telegram_proxy = self.telegram_proxy, None
        if proxy is not None:
            try:
                proxy.stop()
            except Exception:
                pass

    def stop(self) -> None:
        self.disable_telegram_proxy()
        proxy, self.proxy = self.proxy, None
        client, self.client = self.client, None
        if proxy is not None:
            try:
                proxy.stop()
            except Exception:
                pass
        if client is not None:
            try:
                client.close()
            except Exception:
                pass
        self.connected_at = 0.0
        self.latency_ms = -1

    def is_active(self) -> bool:
        if self.client is None or self.proxy is None:
            return False
        transport = self.client.get_transport()
        return bool(transport and transport.is_active())

    def traffic(self) -> tuple[int, int]:
        uploaded = downloaded = 0
        for proxy in (self.proxy, self.telegram_proxy):
            if proxy is not None:
                current_up, current_down = proxy.traffic()
                uploaded += current_up
                downloaded += current_down
        return uploaded, downloaded

    def measure_latency(self) -> int:
        transport = self.client.get_transport() if self.client else None
        if transport is None or not transport.is_active():
            raise ConnectionError("SSH-транспорт не подключён")
        started = time.perf_counter()
        channel = transport.open_channel(
            "direct-tcpip", ("1.1.1.1", 443), ("127.0.0.1", 0), timeout=5
        )
        try:
            latency = max(1, round((time.perf_counter() - started) * 1000))
            self.latency_ms = latency
            return latency
        finally:
            channel.close()

    def uptime(self) -> int:
        if not self.connected_at:
            return 0
        return max(0, int(time.monotonic() - self.connected_at))
