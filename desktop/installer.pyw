from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import winreg
from pathlib import Path
from tkinter import messagebox

import customtkinter as ctk
from PIL import Image, ImageTk


APP_VERSION = "1.38.0"
PRODUCT_NAME = "Пельмени VPN Desktop"
PUBLISHER = "Pelmeni VPN"
APP_EXE = "PelmeniVPN-Desktop.exe"
UNINSTALL_EXE = "Uninstall.exe"
UNINSTALL_KEY = (
    r"Software\Microsoft\Windows\CurrentVersion\Uninstall\PelmeniVPNDesktop"
)
MIDNIGHT = "#0E0E11"
ONYX = "#1C1D21"
SLATE = "#2C2D30"
MUTED = "#878B91"
PALE = "#D7D8DB"
ACCENT = "#FBB26A"
ACCENT_HOVER = "#FFC78F"


def resource_path(relative: str) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / relative


def install_dir() -> Path:
    program_files = Path(os.environ.get("ProgramFiles", r"C:\Program Files"))
    return program_files / "PelmeniVPN Desktop"


def _validated_install_dir(path: Path) -> Path:
    resolved = path.resolve()
    expected = install_dir().resolve()
    if resolved != expected or resolved.name != "PelmeniVPN Desktop":
        raise ValueError(f"Небезопасный путь установки: {resolved}")
    return resolved


def is_admin() -> bool:
    return os.name == "nt" and bool(ctypes.windll.shell32.IsUserAnAdmin())


def relaunch_as_admin() -> None:
    if is_admin():
        return
    params = subprocess.list2cmdline(sys.argv[1:])
    ret = ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, params, None, 1)
    if int(ret or 0) > 32:
        sys.exit(0)


def _startupinfo() -> subprocess.STARTUPINFO:
    info = subprocess.STARTUPINFO()
    info.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    return info


def app_is_running() -> bool:
    try:
        result = subprocess.run(
            ["tasklist", "/FI", f"IMAGENAME eq {APP_EXE}", "/FO", "CSV", "/NH"],
            capture_output=True,
            text=True,
            errors="ignore",
            startupinfo=_startupinfo(),
            check=False,
        )
        return APP_EXE.lower() in result.stdout.lower()
    except Exception:
        return False


def _shortcut_paths() -> tuple[Path, Path, Path]:
    public = Path(os.environ.get("PUBLIC", Path.home()))
    program_data = Path(os.environ.get("ProgramData", r"C:\ProgramData"))
    menu = program_data / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Пельмени VPN"
    return public / "Desktop" / "Пельмени VPN.lnk", menu / "Пельмени VPN.lnk", menu / "Удалить Пельмени VPN.lnk"


def create_shortcut(link: Path, target: Path, arguments: str = "") -> None:
    link.parent.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment.update(
        {
            "PELMENI_LINK": str(link),
            "PELMENI_TARGET": str(target),
            "PELMENI_WORKDIR": str(target.parent),
            "PELMENI_ARGS": arguments,
        }
    )
    script = (
        "$w=New-Object -ComObject WScript.Shell;"
        "$s=$w.CreateShortcut($env:PELMENI_LINK);"
        "$s.TargetPath=$env:PELMENI_TARGET;"
        "$s.WorkingDirectory=$env:PELMENI_WORKDIR;"
        "$s.IconLocation=$env:PELMENI_TARGET+',0';"
        "$s.Arguments=$env:PELMENI_ARGS;"
        "$s.Save()"
    )
    subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
        env=environment,
        startupinfo=_startupinfo(),
        check=True,
    )


def remove_shortcuts() -> None:
    desktop_link, menu_link, uninstall_link = _shortcut_paths()
    for link in (desktop_link, menu_link, uninstall_link):
        link.unlink(missing_ok=True)
    try:
        menu_link.parent.rmdir()
    except OSError:
        pass


def installed_version() -> str:
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, UNINSTALL_KEY) as key:
            return str(winreg.QueryValueEx(key, "DisplayVersion")[0])
    except OSError:
        return ""


def write_uninstall_registry(target: Path) -> None:
    app = target / APP_EXE
    uninstaller = target / UNINSTALL_EXE
    size_kib = sum(
        file.stat().st_size for file in target.iterdir() if file.is_file()
    ) // 1024
    with winreg.CreateKeyEx(
        winreg.HKEY_LOCAL_MACHINE, UNINSTALL_KEY, 0, winreg.KEY_WRITE
    ) as key:
        values = {
            "DisplayName": PRODUCT_NAME,
            "DisplayVersion": APP_VERSION,
            "Publisher": PUBLISHER,
            "InstallLocation": str(target),
            "DisplayIcon": str(app),
            "UninstallString": f'"{uninstaller}" --uninstall',
            "QuietUninstallString": f'"{uninstaller}" --uninstall --silent',
        }
        for name, value in values.items():
            winreg.SetValueEx(key, name, 0, winreg.REG_SZ, value)
        winreg.SetValueEx(key, "NoModify", 0, winreg.REG_DWORD, 1)
        winreg.SetValueEx(key, "NoRepair", 0, winreg.REG_DWORD, 1)
        winreg.SetValueEx(key, "EstimatedSize", 0, winreg.REG_DWORD, size_kib)


def remove_uninstall_registry() -> None:
    try:
        winreg.DeleteKey(winreg.HKEY_LOCAL_MACHINE, UNINSTALL_KEY)
    except FileNotFoundError:
        pass


def install_application(desktop_shortcut: bool = True) -> Path:
    if app_is_running():
        raise RuntimeError(
            "Пельмени VPN сейчас запущен. Закрой приложение и повтори установку."
        )
    target = _validated_install_dir(install_dir())
    target.mkdir(parents=True, exist_ok=True)
    payload = resource_path(f"payload/{APP_EXE}")
    if not payload.exists() or payload.read_bytes()[:2] != b"MZ":
        raise RuntimeError("Установочный пакет повреждён: приложение не найдено")

    app_target = target / APP_EXE
    temporary_app = target / f"{APP_EXE}.new"
    shutil.copy2(payload, temporary_app)
    os.replace(temporary_app, app_target)

    helper_payload = resource_path("payload/PelmeniVPN-TunHelper.exe")
    if helper_payload.exists():
        helper_target = target / "PelmeniVPN-TunHelper.exe"
        temporary_helper = target / "PelmeniVPN-TunHelper.exe.new"
        shutil.copy2(helper_payload, temporary_helper)
        os.replace(temporary_helper, helper_target)

    service_payload = resource_path("payload/PelmeniVPN-Service.exe")
    if service_payload.exists():
        service_target = target / "PelmeniVPN-Service.exe"
        temporary_service = target / "PelmeniVPN-Service.exe.new"
        shutil.copy2(service_payload, temporary_service)
        os.replace(temporary_service, service_target)
        try:
            subprocess.run([str(service_target), "--install"], check=False, startupinfo=_startupinfo())
        except Exception:
            pass

    runtime_payload = resource_path("payload/runtime")
    if runtime_payload.exists() and runtime_payload.is_dir():
        runtime_target = target / "runtime"
        runtime_target.mkdir(parents=True, exist_ok=True)
        for item in runtime_payload.iterdir():
            if item.is_file():
                shutil.copy2(item, runtime_target / item.name)

    uninstaller = target / UNINSTALL_EXE
    temporary_uninstaller = target / f"{UNINSTALL_EXE}.new"
    shutil.copy2(Path(sys.executable), temporary_uninstaller)
    os.replace(temporary_uninstaller, uninstaller)

    manifest = {
        "product": PRODUCT_NAME,
        "version": APP_VERSION,
        "installed_at": int(time.time()),
        "payload_sha256": hashlib.sha256(app_target.read_bytes()).hexdigest(),
    }
    (target / "install.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    desktop_link, menu_link, uninstall_link = _shortcut_paths()
    if desktop_shortcut:
        create_shortcut(desktop_link, app_target)
    else:
        desktop_link.unlink(missing_ok=True)
    create_shortcut(menu_link, app_target)
    create_shortcut(uninstall_link, uninstaller, "--uninstall")
    write_uninstall_registry(target)
    return app_target


def start_uninstall(silent: bool = False) -> None:
    target = _validated_install_dir(install_dir())
    if app_is_running():
        if not silent:
            messagebox.showerror(
                PRODUCT_NAME,
                "Закрой Пельмени VPN перед удалением и повтори попытку.",
            )
        raise SystemExit(2)
    if not silent and not messagebox.askyesno(
        PRODUCT_NAME,
        "Удалить Пельмени VPN Desktop?\n\nНастройки серверов останутся в AppData.",
    ):
        return

    service_exe = target / "PelmeniVPN-Service.exe"
    if service_exe.is_file():
        try:
            subprocess.run([str(service_exe), "--uninstall"], startupinfo=_startupinfo(), timeout=15, check=False)
        except Exception:
            pass

    app = target / APP_EXE
    if app.exists():
        subprocess.run(
            [str(app), "--restore-proxy"],
            startupinfo=_startupinfo(),
            timeout=20,
            check=False,
        )
    remove_shortcuts()
    remove_uninstall_registry()

    helper = Path(tempfile.gettempdir()) / f"PelmeniVPN-Uninstall-{os.getpid()}.exe"
    shutil.copy2(Path(sys.executable), helper)
    subprocess.Popen(
        [str(helper), "--finish-uninstall", str(target), str(os.getpid())],
        startupinfo=_startupinfo(),
    )


def finish_uninstall(target_text: str, parent_pid: int) -> None:
    target = _validated_install_dir(Path(target_text))
    for _ in range(100):
        try:
            os.kill(parent_pid, 0)
        except OSError:
            break
        time.sleep(0.1)
    shutil.rmtree(target, ignore_errors=False)
    ctypes.windll.user32.MessageBoxW(
        None,
        "Пельмени VPN Desktop удалён. Настройки серверов сохранены в AppData.",
        PRODUCT_NAME,
        0x40,
    )
    ctypes.windll.kernel32.MoveFileExW(str(Path(sys.executable)), None, 4)


def self_test() -> None:
    payload = resource_path(f"payload/{APP_EXE}")
    if not payload.exists() or payload.read_bytes()[:2] != b"MZ":
        raise RuntimeError("Installer payload self-test failed")
    with tempfile.TemporaryDirectory(prefix="pelmeni-installer-test-") as folder:
        copied = Path(folder) / APP_EXE
        shutil.copy2(payload, copied)
        if hashlib.sha256(payload.read_bytes()).digest() != hashlib.sha256(
            copied.read_bytes()
        ).digest():
            raise RuntimeError("Installer copy self-test failed")
    print("Pelmeni VPN Desktop installer self-test: OK")


class InstallerWindow:
    def __init__(self, root: ctk.CTk) -> None:
        self.root = root
        self.installed = installed_version()
        self.app_path: Path | None = None
        self.root.title(f"Установка — {PRODUCT_NAME}")
        self.root.geometry("780x500")
        self.root.resizable(False, False)
        self.root.configure(fg_color=MIDNIGHT)
        self._set_icon()
        self._build()

    def _set_icon(self) -> None:
        icon_path = resource_path("assets/pelmeni_icon.png")
        if icon_path.exists():
            image = Image.open(icon_path).convert("RGBA")
            self.window_icon = ImageTk.PhotoImage(image.resize((64, 64)))
            self.root.iconphoto(True, self.window_icon)

    def _build(self) -> None:
        left = ctk.CTkFrame(
            self.root, width=270, fg_color=ACCENT, corner_radius=0
        )
        left.pack(side="left", fill="y")
        left.pack_propagate(False)
        icon_path = resource_path("assets/pelmeni_icon.png")
        if icon_path.exists():
            image = Image.open(icon_path).convert("RGBA")
            self.logo = ctk.CTkImage(image, image, size=(150, 150))
            ctk.CTkLabel(left, text="", image=self.logo).pack(pady=(74, 18))
        ctk.CTkLabel(
            left,
            text="ПЕЛЬМЕНИ VPN",
            text_color=MIDNIGHT,
            font=("Segoe UI Semibold", 23),
        ).pack()
        ctk.CTkLabel(
            left,
            text="Безопасный SSH-туннель\nдля Windows",
            text_color="#34302C",
            justify="center",
            font=("Segoe UI", 12),
        ).pack(pady=(7, 0))

        right = ctk.CTkFrame(self.root, fg_color=MIDNIGHT, corner_radius=0)
        right.pack(side="left", fill="both", expand=True, padx=40, pady=34)
        title = "Обновление приложения" if self.installed else "Установка приложения"
        subtitle = (
            f"Установлена версия {self.installed}. Она будет заменена на {APP_VERSION}, "
            "а настройки серверов сохранятся."
            if self.installed
            else f"Версия {APP_VERSION} будет установлена для всех пользователей компьютера."
        )
        ctk.CTkLabel(
            right,
            text=title,
            text_color=PALE,
            font=("Segoe UI Semibold", 24),
        ).pack(anchor="w", pady=(12, 6))
        ctk.CTkLabel(
            right,
            text=subtitle,
            text_color=MUTED,
            wraplength=410,
            justify="left",
            font=("Segoe UI", 11),
        ).pack(anchor="w")
        ctk.CTkLabel(
            right,
            text=f"Папка установки\n{install_dir()}",
            text_color=PALE,
            fg_color=ONYX,
            corner_radius=14,
            width=410,
            height=70,
            justify="left",
            anchor="w",
            padx=16,
            font=("Segoe UI", 10),
        ).pack(fill="x", pady=(24, 16))
        self.desktop_shortcut = tk.BooleanVar(value=True)
        ctk.CTkSwitch(
            right,
            text="Создать ярлык на рабочем столе",
            variable=self.desktop_shortcut,
            text_color=PALE,
            progress_color=ACCENT,
            button_color=PALE,
            button_hover_color=PALE,
        ).pack(anchor="w")
        self.progress = ctk.CTkProgressBar(
            right,
            mode="indeterminate",
            progress_color=ACCENT,
            fg_color=SLATE,
            height=8,
        )
        self.progress.pack(fill="x", pady=(24, 7))
        self.progress.set(0)
        self.status = ctk.CTkLabel(
            right, text="Готово к установке", text_color=MUTED, font=("Segoe UI", 10)
        )
        self.status.pack(anchor="w")
        self.primary = ctk.CTkButton(
            right,
            text="ОБНОВИТЬ" if self.installed else "УСТАНОВИТЬ",
            command=self.install,
            fg_color=ACCENT,
            hover_color=ACCENT_HOVER,
            text_color=MIDNIGHT,
            corner_radius=12,
            height=46,
            font=("Segoe UI Semibold", 11),
        )
        self.primary.pack(fill="x", pady=(20, 0))

    def install(self) -> None:
        if app_is_running():
            messagebox.showwarning(
                PRODUCT_NAME,
                "Закрой запущенный Пельмени VPN и нажми кнопку установки ещё раз.",
            )
            return
        if not is_admin():
            relaunch_as_admin()
            self.root.destroy()
            return
        self.primary.configure(state="disabled")
        self.status.configure(text="Устанавливаем приложение…")
        self.progress.start()

        def worker() -> None:
            try:
                self.app_path = install_application(bool(self.desktop_shortcut.get()))
                self.root.after(0, self._complete)
            except Exception as error:
                self.root.after(0, lambda: self._failed(str(error)))

        threading.Thread(target=worker, name="pelmeni-install", daemon=True).start()

    def _complete(self) -> None:
        self.progress.stop()
        self.progress.set(1)
        self.status.configure(text="Установка завершена", text_color=ACCENT)
        self.primary.configure(
            state="normal", text="ЗАПУСТИТЬ ПЕЛЬМЕНИ VPN", command=self.launch
        )

    def _failed(self, text: str) -> None:
        self.progress.stop()
        self.progress.set(0)
        self.status.configure(text="Установка не завершена", text_color="#EB5757")
        self.primary.configure(state="normal")
        messagebox.showerror(PRODUCT_NAME, text)

    def launch(self) -> None:
        if self.app_path is not None and self.app_path.exists():
            subprocess.Popen([str(self.app_path)], cwd=str(self.app_path.parent))
        self.root.destroy()


def main() -> None:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--uninstall", action="store_true")
    parser.add_argument("--silent", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--finish-uninstall", nargs=2, metavar=("TARGET", "PID"))
    args, _ = parser.parse_known_args()
    if args.self_test:
        self_test()
        return
    if args.finish_uninstall:
        finish_uninstall(args.finish_uninstall[0], int(args.finish_uninstall[1]))
        return
    if args.uninstall:
        root = ctk.CTk()
        root.withdraw()
        start_uninstall(args.silent)
        root.destroy()
        return

    ctk.set_appearance_mode("dark")
    root = ctk.CTk()
    InstallerWindow(root)
    root.mainloop()


if __name__ == "__main__":
    main()
