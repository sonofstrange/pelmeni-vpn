from __future__ import annotations

import threading
import time
from typing import Any

from PySide6.QtCore import Property, QObject, QTimer, Signal, Slot

from .storage import APP_DIR, load_config, save_config
from .tunnel import TunnelManager
from .tun_broker import ElevatedTunManager
from .version import APP_VERSION
from .windows_proxy import restore_proxy, restore_stale_proxy


def _format_bytes(value: int) -> str:
    amount = float(max(0, value))
    for unit in ("Б", "КБ", "МБ", "ГБ", "ТБ"):
        if amount < 1024 or unit == "ТБ":
            return f"{amount:.0f} {unit}" if unit == "Б" else f"{amount:.1f} {unit}"
        amount /= 1024
    return "0 Б"


class DesktopBackend(QObject):
    stateChanged = Signal()
    profileChanged = Signal()
    hostKeyRequested = Signal(str, str, str)
    messageRequested = Signal(str, str)
    openServerEditorRequested = Signal()

    def __init__(self) -> None:
        super().__init__()
        self.config = load_config()
        self.manager = TunnelManager()
        self.tun = ElevatedTunManager()
        self._proxy = False
        self._vpn = False
        self._connecting = False
        self._stopping = False
        self._intentional_stop = False
        self._status = "Отключено"
        self._speed = "0 Б/с"
        self._download = "0 Б"
        self._upload = "0 Б"
        self._last_sample_time = time.monotonic()
        self._last_sample_bytes = 0
        self._traffic_committed = False
        self._failure_count = 0
        self._ping_inflight = False
        self._next_ping_at = 0.0
        self._host_answer = False
        self._host_event: threading.Event | None = None
        restore_stale_proxy()
        self.tun.restore_stale()
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._tick)
        self._timer.start(1000)

    @Property(str, notify=stateChanged)
    def status(self) -> str:
        return self._status

    @Property(bool, notify=stateChanged)
    def proxyActive(self) -> bool:
        return self._proxy and self.manager.is_active() and self.manager.is_telegram_proxy_active()

    @Property(bool, notify=stateChanged)
    def vpnActive(self) -> bool:
        return self._vpn and self.manager.is_active() and self.tun.is_active()

    @Property(bool, notify=stateChanged)
    def proxyPending(self) -> bool:
        return self._proxy and self._connecting

    @Property(bool, notify=stateChanged)
    def vpnPending(self) -> bool:
        return self._vpn and self._connecting

    @Property(bool, notify=stateChanged)
    def busy(self) -> bool:
        return self._connecting or self._stopping

    @Property(str, notify=stateChanged)
    def speedText(self) -> str:
        return self._speed

    @Property(str, notify=stateChanged)
    def pingText(self) -> str:
        return f"{self.manager.latency_ms} мс" if self.manager.latency_ms >= 0 else "—"

    @Property(str, notify=stateChanged)
    def sessionDown(self) -> str:
        return self._download

    @Property(str, notify=stateChanged)
    def totalDown(self) -> str:
        current = self.manager.traffic()[1] if self.manager.is_active() else 0
        return _format_bytes(int(self.config.get("total_down", 0)) + current)

    @Property(str, notify=stateChanged)
    def totalUp(self) -> str:
        current = self.manager.traffic()[0] if self.manager.is_active() else 0
        return _format_bytes(int(self.config.get("total_up", 0)) + current)
    @Property(str, notify=stateChanged)
    def sessionUp(self) -> str:
        return self._upload

    @Property(str, notify=profileChanged)
    def serverName(self) -> str:
        return str(self.config["profile"].get("name") or "Сервер не добавлен")

    @Property(str, notify=profileChanged)
    def serverAddress(self) -> str:
        profile = self.config["profile"]
        host = str(profile.get("host") or "")
        return f"{host}:{profile.get('port', 22)}" if host else "Добавь первый сервер"

    @Property(str, constant=True)
    def version(self) -> str:
        return APP_VERSION

    @Property(str, constant=True)
    def dataPath(self) -> str:
        return str(APP_DIR)

    @Property(bool, notify=profileChanged)
    def autoReconnect(self) -> bool:
        return bool(self.config.get("auto_reconnect", True))

    @Slot(bool)
    def setAutoReconnect(self, value: bool) -> None:
        self.config["auto_reconnect"] = bool(value)
        save_config(self.config)
        self.profileChanged.emit()

    def _profile_ready(self) -> bool:
        p = self.config["profile"]
        return bool(p.get("host") and p.get("username") and p.get("password"))

    @Slot(str)
    def toggleMode(self, mode: str) -> None:
        if self.busy:
            return
        if not self._profile_ready():
            self.messageRequested.emit("Сервер не настроен", "Сначала добавь SSH-сервер.")
            self.openServerEditorRequested.emit()
            return
        next_proxy, next_vpn = self._proxy, self._vpn
        if mode == "proxy":
            next_proxy = not next_proxy
        else:
            next_vpn = not next_vpn
        if self.manager.is_active():
            if not next_proxy and not next_vpn:
                self.stopConnection()
                return
            mode_changed = (
                (mode == "vpn" and next_vpn != self._vpn)
                or (mode == "proxy" and next_proxy != self._proxy)
            )
            if mode_changed:
                previous_proxy, previous_vpn = self._proxy, self._vpn
                self._proxy, self._vpn = next_proxy, next_vpn
                self._connecting = True
                self._set_status()

                def worker() -> None:
                    try:
                        if mode == "vpn":
                            if next_vpn:
                                self._enable_vpn()
                            else:
                                self._disable_vpn()
                        elif next_proxy:
                            self.manager.enable_telegram_proxy(
                                int(self.config["profile"]["socks_port"])
                            )
                        else:
                            self.manager.disable_telegram_proxy()
                        QTimer.singleShot(0, self, self._mode_reconfigured)
                    except Exception as error:
                        if mode == "vpn" and next_vpn:
                            self._disable_vpn()
                        if mode == "proxy" and next_proxy:
                            self.manager.disable_telegram_proxy()
                        QTimer.singleShot(
                            0,
                            self,
                            lambda e=str(error), p=previous_proxy, v=previous_vpn, m=mode: self._mode_reconfigure_failed(e, p, v, m),
                        )

                threading.Thread(target=worker, name=f"pelmeni-{mode}-mode", daemon=True).start()
                return
            self._proxy, self._vpn = next_proxy, next_vpn
            self._remember_modes()
            self._set_status()
            return
        if next_proxy or next_vpn:
            self._proxy, self._vpn = next_proxy, next_vpn
            self._start_connection()

    def _enable_vpn(self) -> None:
        profile = self.config["profile"]
        mode = str(self.config.get("split_mode", "")) if self.config.get("split_enabled") else ""
        entries = list(self.config.get("split_entries") or [])
        self.tun.start(
            self.manager.vpn_socks_port,
            str(profile["host"]),
            int(profile.get("mtu", 1500)),
            mode,
            entries,
        )

    def _disable_vpn(self) -> None:
        self.tun.stop()
        # Clean up the legacy Windows proxy left by versions up to 1.32.
        try:
            restore_proxy()
        except Exception:
            pass

    @Slot()
    def _mode_reconfigured(self) -> None:
        self._connecting = False
        self._remember_modes()
        self._set_status()

    def _mode_reconfigure_failed(
        self,
        text: str,
        previous_proxy: bool,
        previous_vpn: bool,
        mode: str,
    ) -> None:
        self._connecting = False
        self._proxy, self._vpn = previous_proxy, previous_vpn
        self._set_status()
        title = "Не удалось переключить VPN" if mode == "vpn" else "Не удалось переключить прокси"
        self.messageRequested.emit(title, text)
    def _remember_modes(self) -> None:
        if self._proxy or self._vpn:
            self.config["last_proxy_mode"] = self._proxy
            self.config["last_vpn_mode"] = self._vpn
            save_config(self.config)

    def _set_status(self, text: str | None = None) -> None:
        if text is not None:
            self._status = text
        elif self._connecting:
            self._status = "Подключение…"
        elif self._stopping:
            self._status = "Отключение…"
        elif self.manager.is_active():
            modes = [name for enabled, name in ((self._proxy, "Прокси"), (self._vpn, "VPN")) if enabled]
            self._status = "Подключено · " + " + ".join(modes)
        else:
            self._status = "Отключено"
        self.stateChanged.emit()

    def _start_connection(self) -> None:
        if self._connecting or self.manager.is_active():
            return
        self._intentional_stop = False
        self._connecting = True
        self._traffic_committed = False
        self._remember_modes()
        self._set_status()

        def worker() -> None:
            try:
                self.manager.start(self.config["profile"], self._ask_host_key, self._proxy)
                if self._vpn:
                    self._enable_vpn()
                QTimer.singleShot(0, self, self._connected)
            except Exception as error:
                self._disable_vpn()
                self.manager.stop()
                QTimer.singleShot(0, self, lambda e=str(error): self._connection_failed(e))

        threading.Thread(target=worker, name="pelmeni-qt-connect", daemon=True).start()

    def _ask_host_key(self, host: str, key_type: str, fingerprint: str) -> bool:
        self._host_answer = False
        self._host_event = threading.Event()
        self.hostKeyRequested.emit(host, key_type, fingerprint)
        self._host_event.wait()
        return self._host_answer

    @Slot(bool)
    def answerHostKey(self, accepted: bool) -> None:
        self._host_answer = bool(accepted)
        if self._host_event is not None:
            self._host_event.set()

    @Slot()
    def _connected(self) -> None:
        self._connecting = False
        self._failure_count = 0
        self._next_ping_at = 0.0
        self._last_sample_time = time.monotonic()
        self._last_sample_bytes = 0
        self._set_status("Подключено · " + " + ".join(
            name for enabled, name in ((self._proxy, "Прокси"), (self._vpn, "VPN")) if enabled
        ))

    @Slot(str)
    def _connection_failed(self, text: str) -> None:
        self._connecting = False
        desired_proxy, desired_vpn = self._proxy, self._vpn
        self._failure_count += 1
        if self.config.get("auto_server_failover") and self._failure_count >= 3:
            try:
                from .profile_store import activate, load_profiles
                profiles, active_id = load_profiles()
                candidates = [item for item in profiles if item.get("host")]
                if len(candidates) > 1:
                    current = next((i for i, item in enumerate(candidates) if item["id"] == active_id), 0)
                    selected = activate(candidates[(current + 1) % len(candidates)]["id"])
                    self.config["profile"] = selected
                    save_config(self.config)
                    self.profileChanged.emit()
                    self._failure_count = 0
                    self._proxy, self._vpn = desired_proxy, desired_vpn
                    self._set_status("Переключение на резервный сервер…")
                    QTimer.singleShot(700, self._start_connection)
                    return
            except Exception:
                pass
        self._proxy = False
        self._vpn = False
        self._set_status("Ошибка подключения")
        self.messageRequested.emit("Не удалось подключиться", text)

    def _commit_traffic(self) -> None:
        if self._traffic_committed or not self.manager.is_active():
            return
        uploaded, downloaded = self.manager.traffic()
        self.config["total_down"] = int(self.config.get("total_down", 0)) + downloaded
        self.config["total_up"] = int(self.config.get("total_up", 0)) + uploaded
        save_config(self.config)
        self._traffic_committed = True
    @Slot()
    def stopConnection(self) -> None:
        if self._stopping:
            return
        self._intentional_stop = True
        self._stopping = True
        self._set_status()

        def worker() -> None:
            try:
                self._disable_vpn()
            finally:
                self.manager.stop()
                QTimer.singleShot(0, self, self._stopped)

        threading.Thread(target=worker, name="pelmeni-qt-stop", daemon=True).start()

    @Slot()
    def _stopped(self) -> None:
        self._stopping = False
        self._connecting = False
        self._proxy = False
        self._vpn = False
        self._set_status("Отключено")

    @Slot(str, str, str, str, str, str)
    def saveProfile(self, name: str, host: str, port: str, username: str, password: str, socks_port: str) -> None:
        try:
            profile: dict[str, Any] = {
                "name": name.strip() or "Мой сервер",
                "host": host.strip(),
                "port": int(port),
                "username": username.strip(),
                "password": password,
                "socks_port": int(socks_port),
            }
            if not profile["host"] or not profile["username"] or not profile["password"]:
                raise ValueError("Заполни адрес, пользователя и пароль")
            if not 1 <= profile["port"] <= 65535 or not 1 <= profile["socks_port"] <= 65535:
                raise ValueError("Порт должен быть от 1 до 65535")
            self.config["profile"] = profile
            save_config(self.config)
            self.profileChanged.emit()
            self.messageRequested.emit("Параметры сервера", "Сервер сохранён. Пароль защищён Windows DPAPI.")
        except Exception as error:
            self.messageRequested.emit("Параметры сервера", str(error))

    @Slot(result="QVariantMap")
    def profile(self) -> dict[str, Any]:
        return dict(self.config["profile"])

    @Slot()
    def restoreWindowsProxy(self) -> None:
        try:
            restored = restore_proxy()
            self.messageRequested.emit("Прокси Windows", "Исходные настройки восстановлены." if restored else "Резервной копии настроек нет.")
        except Exception as error:
            self.messageRequested.emit("Прокси Windows", str(error))

    def _request_ping(self) -> None:
        if self._ping_inflight or not self.manager.is_active():
            return
        self._ping_inflight = True

        def worker() -> None:
            try:
                value = self.manager.measure_latency()
            except Exception:
                value = -1
            QTimer.singleShot(0, self, lambda result=value: self._ping_ready(result))

        threading.Thread(target=worker, name="pelmeni-ping", daemon=True).start()

    @Slot(int)
    def _ping_ready(self, value: int) -> None:
        self._ping_inflight = False
        self.manager.latency_ms = int(value) if self.manager.is_active() else -1
        self._next_ping_at = time.monotonic() + 3
        self.stateChanged.emit()


    def _tick(self) -> None:
        active = self.manager.is_active()
        if active:
            uploaded, downloaded = self.manager.traffic()
            total = uploaded + downloaded
            now = time.monotonic()
            if now >= self._next_ping_at:
                self._request_ping()
            elapsed = max(0.001, now - self._last_sample_time)
            self._speed = _format_bytes(round(max(0, total - self._last_sample_bytes) / elapsed)) + "/с"
            self._download = _format_bytes(downloaded)
            self._upload = _format_bytes(uploaded)
            self._last_sample_time = now
            self._last_sample_bytes = total
            self.stateChanged.emit()
        if not active and not self._connecting and not self._stopping and (self._proxy or self._vpn):
            lost_proxy, lost_vpn = self._proxy, self._vpn
            self._proxy = self._vpn = False
            self._disable_vpn()
            self._set_status("Соединение потеряно")
            if self.config.get("auto_reconnect") and not self._intentional_stop:
                self._proxy, self._vpn = lost_proxy, lost_vpn
                QTimer.singleShot(2500, self._start_connection)

    @Slot()
    def shutdown(self) -> None:
        self._intentional_stop = True
        try:
            self._disable_vpn()
        finally:
            self._commit_traffic()
            self.manager.stop()
