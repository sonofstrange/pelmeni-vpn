from __future__ import annotations

import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import messagebox
from typing import Callable

from PIL import Image, ImageTk

from .storage import APP_DIR, load_config, save_config
from .tunnel import TunnelManager
from .windows_proxy import enable_socks_proxy, restore_proxy, restore_stale_proxy


MIDNIGHT = "#0E0E11"
ONYX = "#1C1D21"
SLATE = "#2C2D30"
CHARCOAL = "#494B50"
MUTED = "#878B91"
LIGHT = "#C1C2C5"
PALE = "#D7D8DB"
ACCENT = "#FBB26A"


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


def format_uptime(seconds: int) -> str:
    hours, remainder = divmod(seconds, 3600)
    minutes, secs = divmod(remainder, 60)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}"


class RoundModeButton(tk.Canvas):
    def __init__(self, master: tk.Misc, title: str, command: Callable[[], None]):
        super().__init__(
            master,
            width=140,
            height=140,
            bg=MIDNIGHT,
            highlightthickness=0,
            cursor="hand2",
        )
        self.title = title
        self.command = command
        self.active = False
        self.connecting = False
        self.angle = 0
        self.bind("<Button-1>", lambda _: self.command())
        self._draw()

    def set_state(self, active: bool, connecting: bool = False) -> None:
        was_connecting = self.connecting
        self.active = active
        self.connecting = connecting
        self._draw()
        if connecting and not was_connecting:
            self.after(45, self._animate)

    def _animate(self) -> None:
        try:
            if not self.winfo_exists():
                return
        except tk.TclError:
            return
        if not self.connecting:
            return
        self.angle = (self.angle + 13) % 360
        self._draw()
        self.after(45, self._animate)

    def _draw(self) -> None:
        self.delete("all")
        outline = ACCENT if self.active or self.connecting else CHARCOAL
        self.create_oval(
            6,
            6,
            134,
            134,
            fill=ONYX,
            outline=outline,
            width=4 if self.active else 2,
        )
        if self.connecting:
            self.create_arc(
                2,
                2,
                138,
                138,
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
            70,
            70,
            text=f"{self.title}\n{state}",
            fill=ACCENT if self.active else LIGHT,
            font=("Segoe UI Semibold", 12),
            justify="center",
        )


class PelmeniDesktopApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.config = load_config()
        self.manager = TunnelManager()
        self.proxy_mode = False
        self.vpn_mode = False
        self.connecting = False
        self.stopping = False
        self.intentional_stop = False
        self.page = "home"
        self.entries: dict[str, tk.Entry] = {}

        restore_stale_proxy()
        self._configure_window()
        self._build_shell()
        self.show_home()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(1000, self._tick)

    def _configure_window(self) -> None:
        self.root.title("Пельмени VPN Desktop")
        self.root.configure(bg=MIDNIGHT)
        self.root.geometry("460x780")
        self.root.minsize(430, 680)
        self.root.option_add("*Font", ("Segoe UI", 10))
        icon_path = resource_path("assets/pelmeni_icon.png")
        if icon_path.exists():
            image = Image.open(icon_path).convert("RGBA")
            self.window_icon = ImageTk.PhotoImage(image.resize((64, 64)))
            self.root.iconphoto(True, self.window_icon)

    def _build_shell(self) -> None:
        header = tk.Frame(self.root, bg=MIDNIGHT, height=78)
        header.pack(fill="x", padx=20, pady=(14, 4))
        header.pack_propagate(False)
        icon_path = resource_path("assets/pelmeni_icon.png")
        if icon_path.exists():
            image = Image.open(icon_path).convert("RGBA")
            image.thumbnail((54, 54), Image.Resampling.LANCZOS)
            self.logo = ImageTk.PhotoImage(image)
            tk.Label(header, image=self.logo, bg=MIDNIGHT).pack(side="left")
        title_box = tk.Frame(header, bg=MIDNIGHT)
        title_box.pack(side="left", padx=(10, 0), pady=5)
        tk.Label(
            title_box,
            text="Пельмени VPN",
            bg=MIDNIGHT,
            fg=PALE,
            font=("Segoe UI Semibold", 21),
        ).pack(anchor="w")
        tk.Label(
            title_box,
            text="SSH-туннель для Windows",
            bg=MIDNIGHT,
            fg=MUTED,
        ).pack(anchor="w")

        nav = tk.Frame(self.root, bg=ONYX, height=66)
        nav.pack(fill="x", side="bottom")
        nav.pack_propagate(False)
        self.nav_buttons: dict[str, tk.Button] = {}
        for key, text, action in (
            ("home", "⌂\nГлавная", self.show_home),
            ("server", "＋\nСервер", self.show_server),
            ("settings", "⚙\nНастройки", self.show_settings),
        ):
            button = tk.Button(
                nav,
                text=text,
                command=action,
                bg=ONYX,
                fg=MUTED,
                activebackground=SLATE,
                activeforeground=ACCENT,
                relief="flat",
                bd=0,
                font=("Segoe UI Semibold", 9),
                cursor="hand2",
            )
            button.pack(side="left", fill="both", expand=True)
            self.nav_buttons[key] = button

        self.content = tk.Frame(self.root, bg=MIDNIGHT)
        self.content.pack(fill="both", expand=True, padx=20)

    def _clear_content(self, page: str) -> None:
        self.page = page
        for child in self.content.winfo_children():
            child.destroy()
        for name, button in self.nav_buttons.items():
            button.configure(fg=ACCENT if name == page else MUTED)

    def _panel(self, parent: tk.Misc) -> tk.Frame:
        return tk.Frame(parent, bg=ONYX, highlightthickness=0, padx=16, pady=14)

    def _button(
        self,
        parent: tk.Misc,
        text: str,
        command: Callable[[], None],
        secondary: bool = False,
    ) -> tk.Button:
        return tk.Button(
            parent,
            text=text,
            command=command,
            bg=SLATE if secondary else ACCENT,
            fg=PALE if secondary else MIDNIGHT,
            activebackground=CHARCOAL if secondary else "#FFC78F",
            activeforeground=PALE if secondary else MIDNIGHT,
            relief="flat",
            bd=0,
            padx=16,
            pady=11,
            font=("Segoe UI Semibold", 10),
            cursor="hand2",
        )

    def show_home(self) -> None:
        self._clear_content("home")
        self.status = tk.Label(
            self.content,
            text=self._status_text(),
            bg=MIDNIGHT,
            fg=ACCENT if self.manager.is_active() else MUTED,
            font=("Segoe UI Semibold", 11),
        )
        self.status.pack(pady=(8, 1))
        modes = tk.Frame(self.content, bg=MIDNIGHT)
        modes.pack(pady=(3, 2))
        self.proxy_button = RoundModeButton(
            modes, "ПРОКСИ", lambda: self.toggle_mode("proxy")
        )
        self.proxy_button.pack(side="left", padx=3)
        self.vpn_button = RoundModeButton(
            modes, "VPN", lambda: self.toggle_mode("vpn")
        )
        self.vpn_button.pack(side="left", padx=3)
        self._update_mode_buttons()
        self.metrics = tk.Label(
            self.content,
            text="Сессия 00:00:00 · Пинг —",
            bg=MIDNIGHT,
            fg=MUTED,
        )
        self.metrics.pack(pady=(1, 11))

        profile = self.config["profile"]
        panel = self._panel(self.content)
        panel.pack(fill="x", pady=(0, 10))
        tk.Label(panel, text="Активный сервер", bg=ONYX, fg=MUTED).pack(anchor="w")
        tk.Label(
            panel,
            text=profile.get("name") or "Сервер не настроен",
            bg=ONYX,
            fg=PALE,
            font=("Segoe UI Semibold", 16),
        ).pack(anchor="w", pady=(3, 0))
        address = (
            f"{profile.get('host')}:{profile.get('port')}"
            if profile.get("host")
            else "Добавь первый SSH-сервер"
        )
        tk.Label(panel, text=address, bg=ONYX, fg=MUTED).pack(anchor="w")
        self._button(panel, "ПАРАМЕТРЫ СЕРВЕРА", self.show_server, True).pack(
            fill="x", pady=(10, 0)
        )
        traffic = self._panel(self.content)
        traffic.pack(fill="x")
        tk.Label(
            traffic,
            text="Трафик за сессию",
            bg=ONYX,
            fg=PALE,
            font=("Segoe UI Semibold", 12),
        ).pack(anchor="w")
        self.traffic_label = tk.Label(
            traffic,
            text="↓ 0 Б       ↑ 0 Б",
            bg=ONYX,
            fg=MUTED,
            font=("Segoe UI", 11),
        )
        self.traffic_label.pack(anchor="w", pady=(7, 0))

    def show_server(self) -> None:
        self._clear_content("server")
        tk.Label(
            self.content,
            text="Параметры сервера",
            bg=MIDNIGHT,
            fg=PALE,
            font=("Segoe UI Semibold", 19),
        ).pack(anchor="w", pady=(8, 10))
        panel = self._panel(self.content)
        panel.pack(fill="both", expand=True, pady=(0, 10))
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
        for key, title, secret in fields:
            tk.Label(panel, text=title, bg=ONYX, fg=MUTED).pack(
                anchor="w", pady=(4, 2)
            )
            entry = tk.Entry(
                panel,
                bg=SLATE,
                fg=PALE,
                insertbackground=PALE,
                relief="flat",
                bd=0,
                show="•" if secret else "",
            )
            entry.insert(0, str(profile.get(key, "")))
            entry.pack(fill="x", ipady=6)
            self.entries[key] = entry
        self._button(panel, "СОХРАНИТЬ", self.save_server).pack(
            fill="x", pady=(12, 0)
        )
        tk.Label(
            panel,
            text=(
                "При первом подключении приложение покажет SHA-256 отпечаток "
                "SSH host key. Подтверждай его только после сверки."
            ),
            bg=ONYX,
            fg=MUTED,
            wraplength=370,
            justify="left",
        ).pack(anchor="w", pady=(8, 0))

    def show_settings(self) -> None:
        self._clear_content("settings")
        tk.Label(
            self.content,
            text="Настройки",
            bg=MIDNIGHT,
            fg=PALE,
            font=("Segoe UI Semibold", 19),
        ).pack(anchor="w", pady=(8, 10))
        panel = self._panel(self.content)
        panel.pack(fill="x")
        self.auto_reconnect_var = tk.BooleanVar(
            value=bool(self.config.get("auto_reconnect", True))
        )
        tk.Checkbutton(
            panel,
            text="Автоматически переподключаться",
            variable=self.auto_reconnect_var,
            command=self.save_settings,
            bg=ONYX,
            fg=PALE,
            activebackground=ONYX,
            activeforeground=PALE,
            selectcolor=SLATE,
        ).pack(anchor="w")
        tk.Label(
            panel,
            text=(
                "ПРОКСИ — локальный SOCKS4/SOCKS5 для Telegram и программ.\n\n"
                "VPN — системный прокси Windows. Браузеры и приложения, "
                "поддерживающие его, пойдут через SSH. Это PC beta без Wintun."
            ),
            bg=ONYX,
            fg=MUTED,
            wraplength=370,
            justify="left",
        ).pack(anchor="w", pady=(12, 10))
        self._button(
            panel,
            "ВОССТАНОВИТЬ ПРОКСИ WINDOWS",
            self.restore_windows_proxy,
            True,
        ).pack(fill="x")
        tk.Label(
            panel,
            text=f"Данные: {APP_DIR}",
            bg=ONYX,
            fg=MUTED,
            wraplength=370,
            justify="left",
            font=("Segoe UI", 8),
        ).pack(anchor="w", pady=(10, 0))

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
                fg=ACCENT if self.manager.is_active() else MUTED,
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
            ping = f"{self.manager.latency_ms} мс" if self.manager.latency_ms >= 0 else "—"
            self.metrics.configure(
                text=f"Сессия {format_uptime(self.manager.uptime())} · Пинг {ping}"
            )
            self.traffic_label.configure(
                text=f"↓ {format_bytes(downloaded)}       ↑ {format_bytes(uploaded)}"
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
