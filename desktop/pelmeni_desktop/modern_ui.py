from __future__ import annotations

import sys
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import messagebox
from typing import Callable

import customtkinter as ctk
from PIL import Image, ImageTk

from .storage import APP_DIR, load_config, save_config
from .tunnel import TunnelManager
from .version import APP_VERSION
from .windows_proxy import enable_socks_proxy, restore_proxy, restore_stale_proxy


MIDNIGHT = "#0E0E11"
ONYX = "#1C1D21"
SLATE = "#2C2D30"
CHARCOAL = "#494B50"
MUTED = "#878B91"
LIGHT = "#C1C2C5"
PALE = "#D7D8DB"
ACCENT = "#FBB26A"
ACCENT_HOVER = "#FFC78F"
RED = "#EB5757"


def resource_path(relative: str) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1]))
    return base / relative


def format_bytes(value: int) -> str:
    amount = float(max(0, value))
    for unit in ("Б", "КБ", "МБ", "ГБ", "ТБ"):
        if amount < 1024 or unit == "ТБ":
            return f"{amount:.0f} {unit}" if unit == "Б" else f"{amount:.1f} {unit}"
        amount /= 1024
    return "0 Б"


def format_rate(value: float) -> str:
    return format_bytes(round(max(0.0, value))) + "/с"


def format_uptime(seconds: int) -> str:
    hours, remainder = divmod(seconds, 3600)
    minutes, secs = divmod(remainder, 60)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}"


class RoundModeButton(tk.Canvas):
    def __init__(
        self,
        master: tk.Misc,
        title: str,
        command: Callable[[], None],
        background: str = MIDNIGHT,
    ) -> None:
        super().__init__(
            master,
            width=156,
            height=156,
            bg=background,
            highlightthickness=0,
            cursor="hand2",
        )
        self.title = title
        self.command = command
        self.active = False
        self.connecting = False
        self.hovered = False
        self.angle = 0
        self.bind("<Button-1>", lambda _: self.command())
        self.bind("<Enter>", self._enter)
        self.bind("<Leave>", self._leave)
        self._draw()

    def _enter(self, _event: tk.Event) -> None:
        self.hovered = True
        self._draw()

    def _leave(self, _event: tk.Event) -> None:
        self.hovered = False
        self._draw()

    def set_state(self, active: bool, connecting: bool = False) -> None:
        was_connecting = self.connecting
        self.active = active
        self.connecting = connecting
        self._draw()
        if connecting and not was_connecting:
            self.after(42, self._animate)

    def _animate(self) -> None:
        try:
            if not self.winfo_exists() or not self.connecting:
                return
        except tk.TclError:
            return
        self.angle = (self.angle + 12) % 360
        self._draw()
        self.after(42, self._animate)

    def _draw(self) -> None:
        self.delete("all")
        outline = ACCENT if self.active or self.connecting else CHARCOAL
        fill = "#242529" if self.hovered else ONYX
        self.create_oval(
            8,
            8,
            148,
            148,
            fill=fill,
            outline=outline,
            width=4 if self.active else 2,
        )
        if self.connecting:
            self.create_arc(
                3,
                3,
                153,
                153,
                start=self.angle,
                extent=92,
                style="arc",
                outline=ACCENT,
                width=5,
            )
        state = "ПОДКЛЮЧЕНИЕ" if self.connecting else (
            "ВКЛЮЧЕН" if self.active else "ВКЛЮЧИТЬ"
        )
        self.create_text(
            78,
            72,
            text=self.title,
            fill=ACCENT if self.active else PALE,
            font=("Segoe UI Semibold", 14),
        )
        self.create_text(
            78,
            94,
            text=state,
            fill=ACCENT if self.active else MUTED,
            font=("Segoe UI Semibold", 9),
        )


class PelmeniDesktopApp:
    def __init__(self, root: ctk.CTk) -> None:
        self.root = root
        self.config = load_config()
        self.manager = TunnelManager()
        self.proxy_mode = False
        self.vpn_mode = False
        self.connecting = False
        self.stopping = False
        self.intentional_stop = False
        self.page = "home"
        self.entries: dict[str, ctk.CTkEntry] = {}
        self.content: ctk.CTkScrollableFrame | None = None
        self.last_sample_time = time.monotonic()
        self.last_sample_bytes = 0

        restore_stale_proxy()
        self._configure_window()
        self._build_shell()
        self.show_home()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(1000, self._tick)

    def _configure_window(self) -> None:
        self.root.title("Пельмени VPN Desktop")
        self.root.configure(fg_color=MIDNIGHT)
        self.root.geometry("500x760")
        self.root.minsize(460, 720)
        icon_path = resource_path("assets/pelmeni_icon.png")
        if icon_path.exists():
            image = Image.open(icon_path).convert("RGBA")
            self.window_icon = ImageTk.PhotoImage(image.resize((64, 64)))
            self.root.iconphoto(True, self.window_icon)

    def _build_shell(self) -> None:
        header = ctk.CTkFrame(self.root, fg_color=MIDNIGHT, corner_radius=0, height=88)
        header.pack(fill="x", padx=22, pady=(14, 4))
        header.pack_propagate(False)

        icon_path = resource_path("assets/pelmeni_icon.png")
        if icon_path.exists():
            image = Image.open(icon_path).convert("RGBA")
            self.logo = ctk.CTkImage(light_image=image, dark_image=image, size=(56, 56))
            ctk.CTkLabel(header, text="", image=self.logo).pack(side="left")

        title_box = ctk.CTkFrame(header, fg_color="transparent")
        title_box.pack(side="left", padx=(12, 0), pady=5)
        ctk.CTkLabel(
            title_box,
            text="Пельмени VPN",
            text_color=PALE,
            font=("Segoe UI Semibold", 22),
        ).pack(anchor="w")
        ctk.CTkLabel(
            title_box,
            text="SSH-туннель для Windows",
            text_color=MUTED,
            font=("Segoe UI", 11),
        ).pack(anchor="w")

        ctk.CTkLabel(
            header,
            text="PC BETA",
            text_color=MIDNIGHT,
            fg_color=ACCENT,
            corner_radius=10,
            width=68,
            height=24,
            font=("Segoe UI Semibold", 9),
        ).pack(side="right", pady=12)

        nav = ctk.CTkFrame(self.root, fg_color=ONYX, corner_radius=0, height=72)
        nav.pack(fill="x", side="bottom")
        nav.pack_propagate(False)
        self.nav_buttons: dict[str, ctk.CTkButton] = {}
        for key, text, action in (
            ("home", "●\nГлавная", self.show_home),
            ("server", "＋\nСервер", self.show_server),
            ("settings", "⚙\nНастройки", self.show_settings),
        ):
            button = ctk.CTkButton(
                nav,
                text=text,
                command=action,
                fg_color="transparent",
                hover_color=SLATE,
                text_color=MUTED,
                corner_radius=12,
                font=("Segoe UI Semibold", 10),
            )
            button.pack(side="left", fill="both", expand=True, padx=5, pady=7)
            self.nav_buttons[key] = button

        self.content_host = ctk.CTkFrame(self.root, fg_color=MIDNIGHT, corner_radius=0)
        self.content_host.pack(fill="both", expand=True, padx=(16, 5))

    def _clear_content(self, page: str) -> ctk.CTkScrollableFrame:
        self.page = page
        if self.content is not None:
            self.content.destroy()
        self.content = ctk.CTkScrollableFrame(
            self.content_host,
            fg_color=MIDNIGHT,
            corner_radius=0,
            scrollbar_button_color=SLATE,
            scrollbar_button_hover_color=CHARCOAL,
        )
        self.content.pack(fill="both", expand=True, padx=(3, 8))
        for name, button in self.nav_buttons.items():
            button.configure(
                text_color=ACCENT if name == page else MUTED,
                fg_color=SLATE if name == page else "transparent",
            )
        return self.content

    def _panel(self, parent: tk.Misc) -> ctk.CTkFrame:
        return ctk.CTkFrame(parent, fg_color=ONYX, corner_radius=18)

    def _button(
        self,
        parent: tk.Misc,
        text: str,
        command: Callable[[], None],
        secondary: bool = False,
    ) -> ctk.CTkButton:
        return ctk.CTkButton(
            parent,
            text=text,
            command=command,
            fg_color=SLATE if secondary else ACCENT,
            hover_color=CHARCOAL if secondary else ACCENT_HOVER,
            text_color=PALE if secondary else MIDNIGHT,
            corner_radius=12,
            height=44,
            font=("Segoe UI Semibold", 10),
        )

    def _page_title(self, parent: tk.Misc, title: str, subtitle: str) -> None:
        ctk.CTkLabel(
            parent,
            text=title,
            text_color=PALE,
            font=("Segoe UI Semibold", 21),
        ).pack(anchor="w", padx=4, pady=(8, 0))
        ctk.CTkLabel(
            parent,
            text=subtitle,
            text_color=MUTED,
            font=("Segoe UI", 11),
        ).pack(anchor="w", padx=4, pady=(0, 12))

    def show_home(self) -> None:
        content = self._clear_content("home")
        self.status = ctk.CTkLabel(
            content,
            text=self._status_text(),
            text_color=ACCENT if self.manager.is_active() else MUTED,
            font=("Segoe UI Semibold", 12),
        )
        self.status.pack(pady=(7, 0))

        modes = ctk.CTkFrame(content, fg_color=MIDNIGHT, corner_radius=0)
        modes.pack(pady=(2, 0))
        self.proxy_button = RoundModeButton(
            modes, "ПРОКСИ", lambda: self.toggle_mode("proxy")
        )
        self.proxy_button.pack(side="left", padx=5)
        self.vpn_button = RoundModeButton(
            modes, "VPN", lambda: self.toggle_mode("vpn")
        )
        self.vpn_button.pack(side="left", padx=5)
        self._update_mode_buttons()

        self.metrics = ctk.CTkLabel(
            content,
            text="Скорость: 0 Б/с · Пинг: —",
            text_color=MUTED,
            font=("Segoe UI", 11),
        )
        self.metrics.pack(pady=(0, 12))

        profile = self.config["profile"]
        server = self._panel(content)
        server.pack(fill="x", pady=(0, 10))
        ctk.CTkLabel(server, text="Активный сервер", text_color=MUTED).pack(
            anchor="w", padx=17, pady=(14, 0)
        )
        ctk.CTkLabel(
            server,
            text=profile.get("name") or "Сервер не настроен",
            text_color=PALE,
            font=("Segoe UI Semibold", 17),
        ).pack(anchor="w", padx=17, pady=(2, 0))
        address = (
            f"{profile.get('host')}:{profile.get('port')}"
            if profile.get("host")
            else "Добавь первый SSH-сервер"
        )
        ctk.CTkLabel(server, text=address, text_color=MUTED).pack(
            anchor="w", padx=17
        )
        ctk.CTkLabel(
            server,
            text="Параметры и подтверждённый SSH host key хранятся локально.",
            text_color=MUTED,
            wraplength=390,
            justify="left",
            font=("Segoe UI", 10),
        ).pack(anchor="w", padx=17, pady=(6, 0))
        actions = ctk.CTkFrame(server, fg_color="transparent")
        actions.pack(fill="x", padx=17, pady=(12, 15))
        self._button(actions, "ВЫБРАТЬ", self.show_server, True).pack(
            side="left", fill="x", expand=True, padx=(0, 5)
        )
        self._button(actions, "ПАРАМЕТРЫ", self.show_server, True).pack(
            side="left", fill="x", expand=True, padx=(5, 0)
        )

        traffic = self._panel(content)
        traffic.pack(fill="x", pady=(0, 12))
        ctk.CTkLabel(
            traffic,
            text="Трафик за сессию",
            text_color=PALE,
            font=("Segoe UI Semibold", 13),
        ).pack(anchor="w", padx=17, pady=(14, 8))
        values = ctk.CTkFrame(traffic, fg_color="transparent")
        values.pack(fill="x", padx=17, pady=(0, 14))
        self.download_label = ctk.CTkLabel(
            values, text="↓  0 Б", text_color=PALE, font=("Segoe UI Semibold", 12)
        )
        self.download_label.pack(side="left", fill="x", expand=True, anchor="w")
        self.upload_label = ctk.CTkLabel(
            values, text="↑  0 Б", text_color=PALE, font=("Segoe UI Semibold", 12)
        )
        self.upload_label.pack(side="left", fill="x", expand=True, anchor="w")
        self.session_label = ctk.CTkLabel(
            traffic,
            text="Сессия 00:00:00",
            text_color=MUTED,
            font=("Segoe UI", 10),
        )
        self.session_label.pack(anchor="w", padx=17, pady=(0, 13))

    def show_server(self) -> None:
        content = self._clear_content("server")
        self._page_title(content, "Параметры сервера", "SSH-доступ и локальный SOCKS")
        panel = self._panel(content)
        panel.pack(fill="x", pady=(0, 12))
        profile = self.config["profile"]
        fields = (
            ("name", "Название", False),
            ("host", "IP или домен", False),
            ("port", "SSH-порт", False),
            ("username", "Пользователь", False),
            ("password", "Пароль", True),
            ("socks_port", "Локальный SOCKS-порт", False),
        )
        self.entries = {}
        for index, (key, title, secret) in enumerate(fields):
            ctk.CTkLabel(panel, text=title, text_color=MUTED).pack(
                anchor="w", padx=17, pady=((15 if index == 0 else 9), 3)
            )
            entry = ctk.CTkEntry(
                panel,
                fg_color=SLATE,
                border_color=SLATE,
                text_color=PALE,
                corner_radius=10,
                height=42,
                show="•" if secret else "",
            )
            entry.insert(0, str(profile.get(key, "")))
            entry.pack(fill="x", padx=17)
            self.entries[key] = entry
        self._button(panel, "СОХРАНИТЬ", self.save_server).pack(
            fill="x", padx=17, pady=(16, 0)
        )
        ctk.CTkLabel(
            panel,
            text=(
                "При первом подключении приложение покажет SHA-256 отпечаток "
                "SSH host key. Подтверждай его только после сверки."
            ),
            text_color=MUTED,
            wraplength=390,
            justify="left",
            font=("Segoe UI", 10),
        ).pack(anchor="w", padx=17, pady=(10, 16))

    def show_settings(self) -> None:
        content = self._clear_content("settings")
        self._page_title(content, "Настройки", "Соединение и данные приложения")
        panel = self._panel(content)
        panel.pack(fill="x", pady=(0, 10))
        self.auto_reconnect_var = tk.BooleanVar(
            value=bool(self.config.get("auto_reconnect", True))
        )
        switch = ctk.CTkSwitch(
            panel,
            text="Автоматически переподключаться",
            variable=self.auto_reconnect_var,
            command=self.save_settings,
            text_color=PALE,
            progress_color=ACCENT,
            button_color=PALE,
            button_hover_color=LIGHT,
            font=("Segoe UI Semibold", 11),
        )
        switch.pack(anchor="w", padx=17, pady=(16, 9))
        ctk.CTkLabel(
            panel,
            text=(
                "ПРОКСИ — локальный SOCKS4/SOCKS5 для Telegram и программ.\n\n"
                "VPN — системный прокси Windows. Приложения, использующие "
                "настройки Windows, пойдут через SSH."
            ),
            text_color=MUTED,
            wraplength=390,
            justify="left",
            font=("Segoe UI", 10),
        ).pack(anchor="w", padx=17, pady=(3, 12))
        self._button(
            panel,
            "ВОССТАНОВИТЬ ПРОКСИ WINDOWS",
            self.restore_windows_proxy,
            True,
        ).pack(fill="x", padx=17, pady=(0, 16))

        about = self._panel(content)
        about.pack(fill="x", pady=(0, 12))
        ctk.CTkLabel(
            about,
            text="Пельмени VPN Desktop",
            text_color=PALE,
            font=("Segoe UI Semibold", 14),
        ).pack(anchor="w", padx=17, pady=(14, 2))
        ctk.CTkLabel(
            about,
            text=f"Версия {APP_VERSION}\nДанные: {APP_DIR}",
            text_color=MUTED,
            wraplength=390,
            justify="left",
            font=("Segoe UI", 9),
        ).pack(anchor="w", padx=17, pady=(0, 14))

    def save_server(self) -> None:
        try:
            profile = {
                "name": self.entries["name"].get().strip() or "Мой сервер",
                "host": self.entries["host"].get().strip(),
                "port": int(self.entries["port"].get().strip()),
                "username": self.entries["username"].get().strip(),
                "password": self.entries["password"].get(),
                "socks_port": int(self.entries["socks_port"].get().strip()),
            }
            if not profile["host"] or not profile["username"] or not profile["password"]:
                raise ValueError("Заполни адрес, пользователя и пароль")
            for key in ("port", "socks_port"):
                if not 1 <= int(profile[key]) <= 65535:
                    raise ValueError("Порт должен быть от 1 до 65535")
            self.config["profile"] = profile
            save_config(self.config)
            messagebox.showinfo("Пельмени VPN", "Параметры сервера сохранены")
            self.show_home()
        except Exception as error:
            messagebox.showerror("Параметры сервера", str(error))

    def save_settings(self) -> None:
        self.config["auto_reconnect"] = bool(self.auto_reconnect_var.get())
        save_config(self.config)

    def _profile_ready(self) -> bool:
        profile = self.config["profile"]
        return bool(
            profile.get("host")
            and profile.get("username")
            and profile.get("password")
        )

    def toggle_mode(self, mode: str) -> None:
        if self.connecting or self.stopping:
            return
        if not self._profile_ready():
            messagebox.showinfo("Пельмени VPN", "Сначала добавь SSH-сервер.")
            self.show_server()
            return
        next_proxy = self.proxy_mode
        next_vpn = self.vpn_mode
        if mode == "proxy":
            next_proxy = not next_proxy
        else:
            next_vpn = not next_vpn
        if self.manager.is_active():
            if mode == "vpn":
                try:
                    if next_vpn:
                        enable_socks_proxy(int(self.config["profile"]["socks_port"]))
                    else:
                        restore_proxy()
                except Exception as error:
                    messagebox.showerror("Системный прокси Windows", str(error))
                    return
            self.proxy_mode, self.vpn_mode = next_proxy, next_vpn
            if not self.proxy_mode and not self.vpn_mode:
                self.stop_connection()
            else:
                self._remember_modes()
                self._refresh_home()
            return
        if not next_proxy and not next_vpn:
            return
        self.proxy_mode, self.vpn_mode = next_proxy, next_vpn
        self.start_connection()

    def _remember_modes(self) -> None:
        if self.proxy_mode or self.vpn_mode:
            self.config["last_proxy_mode"] = self.proxy_mode
            self.config["last_vpn_mode"] = self.vpn_mode
            save_config(self.config)

    def start_connection(self) -> None:
        if self.connecting or self.manager.is_active():
            return
        self.intentional_stop = False
        self.connecting = True
        self._remember_modes()
        self._refresh_home("Подключение…")

        def worker() -> None:
            try:
                self.manager.start(self.config["profile"], self._ask_host_key)
                if self.vpn_mode:
                    enable_socks_proxy(int(self.config["profile"]["socks_port"]))
                self.root.after(0, self._connected)
            except Exception as error:
                self.manager.stop()
                try:
                    restore_proxy()
                except Exception:
                    pass
                text = str(error)
                self.root.after(0, lambda: self._connection_failed(text))

        threading.Thread(target=worker, name="pelmeni-connect", daemon=True).start()

    def _ask_host_key(self, host: str, key_type: str, fingerprint: str) -> bool:
        result = {"accepted": False}
        completed = threading.Event()

        def ask() -> None:
            result["accepted"] = messagebox.askyesno(
                "Новый SSH host key",
                (
                    f"Сервер: {host}\n"
                    f"Тип ключа: {key_type}\n"
                    f"Отпечаток: {fingerprint}\n\n"
                    "Сверь отпечаток с владельцем сервера. Доверять этому ключу?"
                ),
            )
            completed.set()

        self.root.after(0, ask)
        completed.wait()
        return bool(result["accepted"])

    def _connected(self) -> None:
        self.connecting = False
        self.last_sample_time = time.monotonic()
        self.last_sample_bytes = 0
        self._refresh_home("Подключено")

    def _connection_failed(self, text: str) -> None:
        self.connecting = False
        self.proxy_mode = False
        self.vpn_mode = False
        self._refresh_home("Ошибка подключения")
        messagebox.showerror("Не удалось подключиться", text)

    def stop_connection(self) -> None:
        if self.stopping:
            return
        self.intentional_stop = True
        self.stopping = True
        self._refresh_home("Отключение…")

        def worker() -> None:
            try:
                restore_proxy()
            finally:
                self.manager.stop()
                self.root.after(0, self._stopped)

        threading.Thread(target=worker, name="pelmeni-stop", daemon=True).start()

    def _stopped(self) -> None:
        self.stopping = False
        self.connecting = False
        self.proxy_mode = False
        self.vpn_mode = False
        self._refresh_home("Отключено")

    def _status_text(self) -> str:
        if self.connecting:
            return "Подключение…"
        if self.stopping:
            return "Отключение…"
        if self.manager.is_active():
            modes = []
            if self.proxy_mode:
                modes.append("Прокси")
            if self.vpn_mode:
                modes.append("VPN")
            return "Подключено · " + " + ".join(modes)
        return "Отключено"

    def _refresh_home(self, status: str | None = None) -> None:
        if self.page != "home":
            return
        if hasattr(self, "status") and self.status.winfo_exists():
            self.status.configure(
                text=status or self._status_text(),
                text_color=ACCENT if self.manager.is_active() else MUTED,
            )
        self._update_mode_buttons()

    def _update_mode_buttons(self) -> None:
        if self.page != "home" or not hasattr(self, "proxy_button"):
            return
        self.proxy_button.set_state(
            self.proxy_mode and self.manager.is_active(),
            self.connecting and self.proxy_mode,
        )
        self.vpn_button.set_state(
            self.vpn_mode and self.manager.is_active(),
            self.connecting and self.vpn_mode,
        )

    def _tick(self) -> None:
        active = self.manager.is_active()
        if self.page == "home" and hasattr(self, "metrics"):
            uploaded, downloaded = self.manager.traffic()
            total = uploaded + downloaded
            now = time.monotonic()
            elapsed = max(0.001, now - self.last_sample_time)
            speed = max(0, total - self.last_sample_bytes) / elapsed
            self.last_sample_time = now
            self.last_sample_bytes = total
            ping = f"{self.manager.latency_ms} мс" if self.manager.latency_ms >= 0 else "—"
            self.metrics.configure(text=f"Скорость: {format_rate(speed)} · Пинг: {ping}")
            self.download_label.configure(text=f"↓  {format_bytes(downloaded)}")
            self.upload_label.configure(text=f"↑  {format_bytes(uploaded)}")
            self.session_label.configure(
                text=f"Сессия {format_uptime(self.manager.uptime())}"
            )
        if (
            not active
            and not self.connecting
            and not self.stopping
            and (self.proxy_mode or self.vpn_mode)
        ):
            lost_proxy, lost_vpn = self.proxy_mode, self.vpn_mode
            self.proxy_mode = False
            self.vpn_mode = False
            try:
                restore_proxy()
            except Exception:
                pass
            self._refresh_home("Соединение потеряно")
            if self.config.get("auto_reconnect") and not self.intentional_stop:
                self.proxy_mode, self.vpn_mode = lost_proxy, lost_vpn
                self.root.after(2500, self.start_connection)
        self.root.after(1000, self._tick)

    def restore_windows_proxy(self) -> None:
        try:
            restored = restore_proxy()
            messagebox.showinfo(
                "Прокси Windows",
                "Исходные настройки восстановлены."
                if restored
                else "Резервной копии настроек нет.",
            )
        except Exception as error:
            messagebox.showerror("Прокси Windows", str(error))

    def close(self) -> None:
        self.intentional_stop = True
        try:
            restore_proxy()
        except Exception:
            pass
        self.manager.stop()
        self.root.destroy()
