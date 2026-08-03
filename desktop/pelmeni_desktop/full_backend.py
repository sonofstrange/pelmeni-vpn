from __future__ import annotations

import json
import threading
import uuid
from pathlib import Path
from typing import Any, Callable

from PySide6.QtCore import Property, QTimer, QUrl, Signal, Slot
from PySide6.QtGui import QDesktopServices, QGuiApplication

from .android_api import (
    ServerPeopleApi,
    check_updates,
    claim_public_server,
    import_safe_config,
    ensure_remote_key,
    load_public_servers,
    profile_from_access_code,
    prepare_public_server,
    safe_export,
    speed_test,
)
from .developer_mode import is_developer_mode, state_revision
from .profile_store import activate, delete, load_profiles, save_and_activate
from .qt_runtime_backend import QtDesktopBackend
from .storage import APP_DIR, load_config, save_config
from .startup import set_start_on_boot
from .tunnel import TunnelManager



def format_data_size(value: int | float) -> str:
    amount = float(max(0, value or 0))
    units = ("Б", "КБ", "МБ", "ГБ", "ТБ")
    unit_index = 0
    while amount >= 1024 and unit_index < len(units) - 1:
        amount /= 1024
        unit_index += 1
    if unit_index == 0 or amount >= 10 or abs(amount - round(amount)) < 0.05:
        number = str(round(amount))
    else:
        number = f"{amount:.1f}".rstrip("0").rstrip(".").replace(".", ",")
    return f"{number} {units[unit_index]}"


class FullDesktopBackend(QtDesktopBackend):
    publicServersChanged = Signal()
    peopleChanged = Signal()
    profilesChanged = Signal()
    settingsChanged = Signal()
    actionBusyChanged = Signal()
    actionFinished = Signal(str, object, str)
    accessCodeReady = Signal(str)

    def __init__(self) -> None:
        self._developer_mode = is_developer_mode()
        self._developer_revision = state_revision()
        super().__init__()
        if not self._developer_mode:
            self.config["beta_updates"] = False
        self._public_servers: list[dict[str, Any]] = []
        self._people: list[dict[str, Any]] = []
        self._action_busy = False
        self.actionFinished.connect(self._finish_action)
        self._developer_timer = QTimer(self)
        self._developer_timer.timeout.connect(self._refresh_developer_mode)
        self._developer_timer.start(1000)
        profiles, active_id = load_profiles()
        active = next((item for item in profiles if item["id"] == active_id), profiles[0] if profiles else None)
        if active and (active.get("host") or not self.config["profile"].get("host")):
            self.config["profile"] = dict(active)
            save_config(self.config)


    @Slot(float, result=str)
    def formatBytes(self, value: float) -> str:
        return format_data_size(value)

    @Property(str, notify=publicServersChanged)
    def publicServersJson(self) -> str:
        return json.dumps(self._public_servers, ensure_ascii=False)

    @Property(str, notify=peopleChanged)
    def peopleJson(self) -> str:
        safe = []
        for user in self._people:
            item = dict(user)
            item.pop("password", None)
            safe.append(item)
        return json.dumps(safe, ensure_ascii=False)

    @Property(str, notify=profilesChanged)
    def profilesJson(self) -> str:
        profiles, active_id = load_profiles()
        safe = []
        for profile in profiles:
            item = {key: value for key, value in profile.items() if key != "password"}
            item["active"] = item["id"] == active_id
            safe.append(item)
        return json.dumps(safe, ensure_ascii=False)

    @Property(bool, notify=actionBusyChanged)
    def actionBusy(self) -> bool:
        return self._action_busy

    def _task(self, kind: str, action: Callable[[], Any]) -> None:
        if self._action_busy:
            self.messageRequested.emit("Пельмени VPN", "Дождись завершения текущей операции.")
            return
        self._action_busy = True
        self.actionBusyChanged.emit()

        def worker() -> None:
            try:
                self.actionFinished.emit(kind, action(), "")
            except Exception as error:
                self.actionFinished.emit(kind, None, str(error) or error.__class__.__name__)

        threading.Thread(target=worker, name=f"pelmeni-{kind}", daemon=True).start()

    @Slot(str, object, str)
    def _finish_action(self, kind: str, result: Any, error: str) -> None:
        self._action_busy = False
        self.actionBusyChanged.emit()
        if error:
            self.messageRequested.emit("Операция не выполнена", error)
            return
        if kind == "public-list":
            self._public_servers = list(result or [])
            self.publicServersChanged.emit()
        elif kind == "people-list":
            self._people = list(result or [])
            self.peopleChanged.emit()
        elif kind in ("people-create", "people-extend", "people-limits", "people-reset", "people-revoke"):
            if kind == "people-create" and isinstance(result, dict) and result.get("access_code"):
                self.accessCodeReady.emit(str(result["access_code"]))
            self.loadPeople()
        elif kind == "public-claim":
            self._apply_access_code(str(result))
        elif kind == "public-prepare":
            QDesktopServices.openUrl(QUrl(str(result["publish_url"])))
            self.messageRequested.emit("Публичный сервер", "Публичный режим подготовлен. В браузере открыта готовая заявка каталога.")
        elif kind == "migration":
            self.config["profile"] = dict(result["profile"])
            save_config(self.config)
            self.profileChanged.emit()
            self.profilesChanged.emit()
            self.messageRequested.emit("Перенос сервера", "Сервер перенесён. Пользователей: " + str(result["users"]) + ".")
        elif kind == "split-import":
            self.config["split_mode"] = result["mode"]
            self.config["split_entries"] = result["entries"]
            save_config(self.config)
            self.settingsChanged.emit()
            self.messageRequested.emit("Раздельное туннелирование", "Набор импортирован.")
        elif kind == "split-export":
            self.messageRequested.emit("Раздельное туннелирование", "Набор сохранён:\n" + str(result))
        elif kind == "export":
            self.messageRequested.emit("Конфигурация экспортирована", f"Файл без пароля сохранён:\n{result}")
        elif kind == "import-file":
            profile, settings = result
            selected = save_and_activate(profile)
            self.config.update(settings)
            self.config["profile"] = selected
            save_config(self.config)
            self.profileChanged.emit()
            self.profilesChanged.emit()
            self.messageRequested.emit("Импорт завершён", "Конфигурация добавлена. Введи пароль сервера, если его нет.")
            if not selected.get("password"):
                self.openServerEditorRequested.emit()
        elif kind == "update":
            if not result:
                self.messageRequested.emit("Обновления", "Установлена актуальная версия.")
            else:
                text = f"Доступна версия {result['version']}.\n\n{result.get('notes','')[:700]}"
                self.messageRequested.emit("Найдено обновление", text)
                if result.get("page_url"):
                    QDesktopServices.openUrl(QUrl(str(result["page_url"])))
        elif kind == "speed":
            self.messageRequested.emit(
                "Тест скорости",
                f"Пинг: {result['latency_ms']} мс\nСкачать: {self._rate(result['download_bps'])}\nЗагрузить: {self._rate(result['upload_bps'])}",
            )
        elif kind in ("tls", "tls-configure"):
            profile = dict(self.config["profile"])
            profile.update(tls_enabled=True, tls_port=443, tls_ports="443", tls_host=profile.get("host", ""))
            selected = save_and_activate(profile)
            self.config["profile"] = selected
            save_config(self.config)
            self.profileChanged.emit()
            self.profilesChanged.emit()
            self.settingsChanged.emit()
            self.messageRequested.emit("TLS-защита готова", "TLS настроен и включён. Сертификат защищён Windows DPAPI.")
        elif kind == "tls-remove":
            profile = dict(self.config["profile"])
            profile["tls_enabled"] = False
            selected = save_and_activate(profile)
            self.config["profile"] = selected
            save_config(self.config)
            self.profileChanged.emit()
            self.profilesChanged.emit()
            self.settingsChanged.emit()
            self.messageRequested.emit("TLS удалён", "TLS-компоненты и локальные ключи удалены.")
        else:
            self.messageRequested.emit("Пельмени VPN", "Операция завершена.")

    @staticmethod
    def _rate(value: int) -> str:
        return f"{value / 1024 / 1024:.1f} МБ/с"

    def _apply_access_code(self, code: str) -> None:
        profile, _policy = profile_from_access_code(code)
        selected = save_and_activate(profile)
        self.config["profile"] = selected
        save_config(self.config)
        self.profileChanged.emit()
        self.profilesChanged.emit()
        if selected.get("tls_enabled"):
            def fetch_access_tls() -> str:
                ensure_remote_key(selected, self._ask_host_key)
                return str(ServerPeopleApi(selected).fetch_tls())
            self._task("tls", fetch_access_tls)
        else:
            self.messageRequested.emit("Сервер добавлен", f"Активирован сервер «{selected['name']}». SSH host key будет проверен при первом подключении.")

    @Property(bool, notify=settingsChanged)
    def tlsEnabled(self) -> bool:
        return bool(self.config.get("profile", {}).get("tls_enabled", False))

    @Property(bool, notify=settingsChanged)
    def tlsConfigured(self) -> bool:
        profile_id = str(self.config.get("profile", {}).get("id") or "active")
        return (APP_DIR / "tls" / (profile_id + ".json")).exists()
    @Property(bool, notify=settingsChanged)
    def autoFailover(self) -> bool:
        return bool(self.config.get("auto_server_failover", False))

    @Property(bool, notify=settingsChanged)
    def startOnBoot(self) -> bool:
        return bool(self.config.get("start_on_boot", False))

    @Property(str, notify=settingsChanged)
    def appName(self) -> str:
        return "Huyna VPN" if self._developer_mode else "Пельмени VPN"

    @Property(bool, notify=settingsChanged)
    def developerMode(self) -> bool:
        return self._developer_mode

    @Property(bool, notify=settingsChanged)
    def betaUpdates(self) -> bool:
        return self._developer_mode and bool(self.config.get("beta_updates", False))

    @Slot()
    def _refresh_developer_mode(self) -> None:
        revision = state_revision()
        if revision == self._developer_revision:
            return
        self._developer_revision = revision
        enabled = is_developer_mode()
        if enabled == self._developer_mode:
            return
        self._developer_mode = enabled
        if not enabled:
            self.config["beta_updates"] = False
            try:
                save_config(self.config)
            except Exception:
                pass
        self.settingsChanged.emit()

    @Property(bool, notify=settingsChanged)
    def splitEnabled(self) -> bool:
        return bool(self.config.get("split_enabled", False))

    @Property(str, notify=settingsChanged)
    def splitMode(self) -> str:
        return str(self.config.get("split_mode", "bypass"))

    @Property(str, notify=settingsChanged)
    def splitEntriesJson(self) -> str:
        return json.dumps(self.config.get("split_entries") or [], ensure_ascii=False)

    @Property(bool, notify=settingsChanged)
    def appSplitEnabled(self) -> bool:
        return bool(self.config.get("app_split_enabled", False))

    @Property(str, notify=settingsChanged)
    def appSplitMode(self) -> str:
        return str(self.config.get("app_split_mode", "bypass"))

    @Property(str, notify=settingsChanged)
    def appSplitAppsJson(self) -> str:
        return json.dumps(self.config.get("app_split_apps") or [], ensure_ascii=False)

    @Slot(str, bool)
    def setBooleanSetting(self, name: str, value: bool) -> None:
        keys = {
            "auto_server_failover", "start_on_boot", "beta_updates",
            "split_enabled", "app_split_enabled",
        }
        if name not in keys:
            return
        if name == "beta_updates" and not self._developer_mode:
            self.config[name] = False
            return
        self.config[name] = bool(value)
        if name == "auto_server_failover" and value:
            self.config["auto_reconnect"] = True
            self.profileChanged.emit()
        if name == "start_on_boot":
            try:
                set_start_on_boot(bool(value))
            except Exception as error:
                self.config[name] = False
                self.messageRequested.emit("Запуск после перезагрузки", str(error))
        save_config(self.config)
        self.settingsChanged.emit()

    @Slot(str, str)
    def setStringSetting(self, name: str, value: str) -> None:
        if name not in {"split_mode", "app_split_mode"}:
            return
        if value not in {"bypass", "only"}:
            return
        self.config[name] = value
        save_config(self.config)
        self.settingsChanged.emit()

    @Slot(str, str)
    def setJsonListSetting(self, name: str, value: str) -> None:
        if name not in {"split_entries", "app_split_apps"}:
            return
        try:
            items = json.loads(value)
            if not isinstance(items, list):
                raise ValueError("Ожидается список")
            cleaned = [str(item).strip() for item in items if str(item).strip()]
            self.config[name] = list(dict.fromkeys(cleaned))
            save_config(self.config)
            self.settingsChanged.emit()
        except Exception as error:
            self.messageRequested.emit("Раздельное туннелирование", str(error))

    @Slot(str)
    def importSplitFile(self, path: str) -> None:
        def action() -> dict[str, Any]:
            source = Path(path.strip().strip('"'))
            if source.stat().st_size > 256 * 1024:
                raise ValueError("Файл слишком большой.")
            data = json.loads(source.read_text(encoding="utf-8"))
            if data.get("format") != 1 or data.get("type") != "pelmeni_split_tunnel":
                raise ValueError("Неподдерживаемый формат.")
            mode = str(data.get("mode", "bypass"))
            if mode not in {"bypass", "only"}:
                raise ValueError("Неизвестный режим.")
            entries = [str(item).strip() for item in data.get("entries", []) if str(item).strip()][:512]
            return {"mode": mode, "entries": list(dict.fromkeys(entries))}
        self._task("split-import", action)

    @Slot(str)
    def exportSplitFile(self, path: str) -> None:
        def action() -> str:
            target = Path(path.strip().strip('"'))
            if not target.suffix:
                target = target.with_suffix(".json")
            target.write_text(json.dumps({
                "format": 1, "type": "pelmeni_split_tunnel", "name": "Свои адреса",
                "mode": self.config.get("split_mode", "bypass"),
                "entries": self.config.get("split_entries") or [],
            }, ensure_ascii=False, indent=2), encoding="utf-8")
            return str(target)
        self._task("split-export", action)

    @Slot(str, str, str, str, str)
    def migrateServer(self, name: str, host: str, port: str, username: str, password: str) -> None:
        old = dict(self.config["profile"])

        def action() -> dict[str, Any]:
            destination = dict(old)
            destination.update({
                "id": str(uuid.uuid4()), "name": name.strip() or old.get("name") or host.strip(),
                "host": host.strip(), "port": int(port), "username": username.strip(),
                "password": password, "tls_enabled": False, "tls_host": host.strip(),
            })
            if not destination["host"] or not destination["username"] or not destination["password"]:
                raise ValueError("Проверь адрес, порт, пользователя и пароль.")
            verifier = TunnelManager()
            try:
                verifier.start(destination, self._ask_host_key)
            finally:
                verifier.stop()
            users = ServerPeopleApi(old).run("export", None)
            tls_was_enabled = bool(old.get("tls_enabled", False))
            if tls_was_enabled:
                ServerPeopleApi(destination).configure_tls()
                destination.update(tls_enabled=True, tls_port=443, tls_ports="443")
            ServerPeopleApi(destination).run("import", {
                "users": users,
                "code_profile": {
                    "name": destination.get("name", destination["host"]),
                    "host": destination["host"], "ssh_port": destination["port"],
                    "socks_port": str(destination.get("socks_port", 1080)),
                    "window_kib": int(destination.get("window_kib", 4096)),
                    "packet_kib": int(destination.get("packet_kib", 32)),
                    "mtu": int(destination.get("mtu", 8500)), "tls_enabled": tls_was_enabled,
                },
            })
            selected = save_and_activate(destination)
            if old.get("id") and old["id"] != selected["id"]:
                try:
                    delete(old["id"])
                except Exception:
                    pass
            return {"profile": selected, "users": len(users)}

        self._task("migration", action)

    @Slot(str, str, int, int, int, int, int, bool)
    def preparePublicServer(
        self, name: str, location: str, days: int, daily_mb: int,
        monthly_mb: int, speed_mbps: int, max_users: int, use_tls: bool,
    ) -> None:
        profile = dict(self.config["profile"])
        self._task("public-prepare", lambda: prepare_public_server(
            profile, name, location, days, daily_mb, monthly_mb,
            speed_mbps, max_users, bool(use_tls),
        ))

    @Slot(str)
    def importAccessCode(self, code: str) -> None:
        try:
            self._apply_access_code(code)
        except Exception as error:
            self.messageRequested.emit("Код доступа", str(error))

    @Slot()
    def exportConfig(self) -> None:
        self._task("export", lambda: str(safe_export(self.config["profile"], self.config)))

    @Slot(str)
    def importConfigFile(self, path: str) -> None:
        self._task("import-file", lambda: import_safe_config(path.strip().strip('"')))

    @Slot()
    def loadPublicServers(self) -> None:
        self._task("public-list", load_public_servers)

    @Slot(int)
    def claimPublicServer(self, index: int) -> None:
        if not 0 <= index < len(self._public_servers):
            self.messageRequested.emit("Бесплатные серверы", "Сервер не найден.")
            return
        entry = dict(self._public_servers[index])
        self._task("public-claim", lambda: claim_public_server(entry))

    @Slot()
    def loadPeople(self) -> None:
        self._task("people-list", lambda: ServerPeopleApi(dict(self.config["profile"])).list())

    @Slot(str, str, int, int, int, int)
    def createPerson(self, label: str, login: str, days: int, daily_mb: int, monthly_mb: int, speed_mbps: int) -> None:
        self._task("people-create", lambda: ServerPeopleApi(dict(self.config["profile"])).create(label, login, days, daily_mb, monthly_mb, speed_mbps))

    @Slot(str, int)
    def extendPerson(self, login: str, days: int) -> None:
        self._task("people-extend", lambda: ServerPeopleApi(dict(self.config["profile"])).extend(login, days))

    @Slot(str)
    def revokePerson(self, login: str) -> None:
        self._task("people-revoke", lambda: ServerPeopleApi(dict(self.config["profile"])).revoke(login))

    @Slot(str, int, int, int)
    def updatePersonLimits(self, login: str, daily_mb: int, monthly_mb: int, speed_mbps: int) -> None:
        self._task("people-limits", lambda: ServerPeopleApi(dict(self.config["profile"])).update_limits(login, daily_mb, monthly_mb, speed_mbps))

    @Slot(str)
    def resetPersonUsage(self, login: str) -> None:
        self._task("people-reset", lambda: ServerPeopleApi(dict(self.config["profile"])).reset_usage(login))

    @Slot()
    def configureTls(self) -> None:
        profile = dict(self.config["profile"])
        def action() -> str:
            ensure_remote_key(profile, self._ask_host_key)
            return str(ServerPeopleApi(profile).configure_tls())
        self._task("tls-configure", action)

    @Slot()
    def removeTls(self) -> None:
        self._task("tls-remove", lambda: ServerPeopleApi(dict(self.config["profile"])).remove_tls())

    @Slot(bool)
    def setTlsEnabled(self, enabled: bool) -> None:
        profile = dict(self.config["profile"])
        profile["tls_enabled"] = bool(enabled) and self.tlsConfigured
        selected = save_and_activate(profile)
        self.config["profile"] = selected
        save_config(self.config)
        self.profileChanged.emit()
        self.profilesChanged.emit()
    @Slot()
    def fetchTlsCertificate(self) -> None:
        self._task("tls", lambda: str(ServerPeopleApi(dict(self.config["profile"])).fetch_tls()))

    @Slot(bool)
    def checkUpdates(self, prereleases: bool = False) -> None:
        include_beta = self._developer_mode and bool(prereleases)
        self._task("update", lambda: check_updates(include_beta))

    @Slot()
    def runSpeedTest(self) -> None:
        profile = dict(self.config["profile"])
        self._task("speed", lambda: speed_test(int(profile.get("socks_port", 1080)), self._vpn))

    @Slot()
    def openTelegramProxy(self) -> None:
        port = int(self.config["profile"].get("socks_port", 1080))
        QDesktopServices.openUrl(QUrl(f"https://t.me/socks?server=127.0.0.1&port={port}"))

    @Slot(str)
    def copyText(self, text: str) -> None:
        QGuiApplication.clipboard().setText(text)
        self.messageRequested.emit("Буфер обмена", "Код скопирован.")

    @Slot(str)
    def activateProfile(self, profile_id: str) -> None:
        try:
            selected = activate(profile_id)
            self.config["profile"] = selected
            save_config(self.config)
            self.profileChanged.emit()
            self.profilesChanged.emit()
        except Exception as error:
            self.messageRequested.emit("Серверы", str(error))

    @Slot(str)
    def deleteProfile(self, profile_id: str) -> None:
        try:
            selected = delete(profile_id)
            self.config["profile"] = selected
            save_config(self.config)
            self.profileChanged.emit()
            self.profilesChanged.emit()
        except Exception as error:
            self.messageRequested.emit("Серверы", str(error))

    @Slot(str, str, str, str, str, str, str)
    def saveProfile(self, name: str, host: str, port: str, username: str, password: str, socks_port: str, profile_id: str) -> None:
        try:
            profiles, _ = load_profiles()
            profile = next((dict(item) for item in profiles if item["id"] == profile_id.strip()), {})
            profile.update({
                "name": name.strip() or "Мой сервер", "host": host.strip(),
                "port": int(port), "username": username.strip(),
                "password": password, "socks_port": int(socks_port),
            })
            if not profile["host"] or not profile["username"] or not profile["password"]:
                raise ValueError("Заполни адрес, пользователя и пароль")
            if not 1 <= profile["port"] <= 65535 or not 1 <= profile["socks_port"] <= 65535:
                raise ValueError("Порт должен быть от 1 до 65535")
            selected = save_and_activate(profile)
            self.config["profile"] = selected
            save_config(self.config)
            self.profileChanged.emit()
            self.profilesChanged.emit()
            self.messageRequested.emit("Параметры сервера", "Сервер сохранён. Пароль защищён Windows DPAPI.")
        except Exception as error:
            self.messageRequested.emit("Параметры сервера", str(error))
