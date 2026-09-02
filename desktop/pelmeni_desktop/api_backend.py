from __future__ import annotations

import base64
import hashlib

import paramiko
from PySide6.QtCore import Slot

from .android_api import _remote_key
from .full_backend import FullDesktopBackend
from .profile_store import save_and_activate
from .storage import KNOWN_HOSTS_PATH, save_config


class DesktopApiBackend(FullDesktopBackend):
    @Slot(int, int, int)
    def saveTuning(self, window_kib: int, packet_kib: int, mtu: int) -> None:
        try:
            if not 128 <= int(window_kib) <= 16384:
                raise ValueError("Окно SSH должно быть от 128 до 16384 КиБ.")
            if not 16 <= int(packet_kib) <= 64:
                raise ValueError("Пакет SSH должен быть от 16 до 64 КиБ.")
            if not 1280 <= int(mtu) <= 16000:
                raise ValueError("MTU должен быть от 1280 до 16000.")
            profile = dict(self.config["profile"])
            profile.update(window_kib=int(window_kib), packet_kib=int(packet_kib), mtu=int(mtu))
            selected = save_and_activate(profile)
            self.config["profile"] = selected
            save_config(self.config)
            self.profileChanged.emit()
            self.profilesChanged.emit()
        except Exception as error:
            self.messageRequested.emit("Производительность", str(error))

    @Slot()
    def checkServerKey(self) -> None:
        profile = dict(self.config["profile"])
        host, port = str(profile.get("host") or ""), int(profile.get("port", 22))
        if not host:
            self.messageRequested.emit("SSH host key", "Сначала укажи сервер.")
            return

        def inspect() -> str:
            key = _remote_key(host, port)
            digest = hashlib.sha256(key.asbytes()).digest()
            fingerprint = "SHA256:" + base64.b64encode(digest).decode().rstrip("=")
            lookup = host if port == 22 else f"[{host}]:{port}"
            known = paramiko.HostKeys()
            if KNOWN_HOSTS_PATH.exists():
                known.load(str(KNOWN_HOSTS_PATH))
            saved = known.lookup(lookup) or known.lookup(host)
            pinned = saved.get(key.get_name()) if saved else None
            if pinned is None or pinned.asbytes() != key.asbytes():
                if not self._ask_host_key(lookup, key.get_name(), fingerprint):
                    raise RuntimeError("Новый SSH host key не подтверждён.")
                if saved:
                    for key_type in list(saved.keys()):
                        known._entries = [entry for entry in known._entries if not (lookup in entry.hostnames and entry.key.get_name() == key_type)]
                known.add(lookup, key.get_name(), key)
                KNOWN_HOSTS_PATH.parent.mkdir(parents=True, exist_ok=True)
                known.save(str(KNOWN_HOSTS_PATH))
                return f"SSH host key подтверждён и закреплён.\nТип: {key.get_name()}\nОтпечаток: {fingerprint}"
            return f"SSH host key совпадает.\nТип: {key.get_name()}\nОтпечаток: {fingerprint}"
        self._task("host-key", inspect)

    @Slot(str, object, str)
    def _finish_action(self, kind: str, result: object, error: str) -> None:
        if kind != "host-key":
            super()._finish_action(kind, result, error)
            return
        self._action_busy = False
        self.actionBusyChanged.emit()
        if error:
            self.messageRequested.emit("SSH host key", error)
        else:
            self.messageRequested.emit("SSH host key", str(result))

    @Slot(result=bool)
    def isServiceInstalled(self) -> bool:
        from .service_client import ServiceClient
        return ServiceClient.is_service_available()

    @Slot()
    def installService(self) -> None:
        from .service import install_service
        try:
            install_service()
            self.settingsChanged.emit()
            self.messageRequested.emit("Системная служба", "Фоновая служба PelmeniVPNService установлена и запущена.\nТеперь VPN включается мгновенно без запросов UAC!")
        except Exception as error:
            self.messageRequested.emit("Ошибка установки службы", str(error))

    @Slot()
    def uninstallService(self) -> None:
        from .service import uninstall_service
        try:
            uninstall_service()
            self.settingsChanged.emit()
            self.messageRequested.emit("Системная служба", "Фоновая служба PelmeniVPNService успешно удалена.")
        except Exception as error:
            self.messageRequested.emit("Ошибка удаления службы", str(error))
