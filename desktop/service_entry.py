from __future__ import annotations

import argparse
import ctypes
import os
import subprocess
import sys
from pathlib import Path

from pelmeni_desktop.service import (
    PelmeniWindowsService,
    SERVICE_NAME,
    SERVICE_DISPLAY_NAME,
    SERVICE_DESCRIPTION,
    run_service_worker,
)


def _is_admin() -> bool:
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def _elevate_self(args: list[str]) -> int:
    executable = sys.executable if getattr(sys, "frozen", False) else sys.executable
    script_args = args if getattr(sys, "frozen", False) else [str(Path(sys.argv[0]).resolve()), *args]
    params = subprocess.list2cmdline(script_args)
    shell_execute = ctypes.windll.shell32.ShellExecuteW
    shell_execute.restype = ctypes.c_void_p
    res = shell_execute(None, "runas", executable, params, str(Path(executable).parent), 1)
    return 0 if int(res or 0) > 32 else 1


def install_service() -> int:
    if not _is_admin():
        return _elevate_self(["--install"])

    target_exe = sys.executable if getattr(sys, "frozen", False) else sys.executable
    if not getattr(sys, "frozen", False):
        bin_path = f'"{target_exe}" "{Path(sys.argv[0]).resolve()}" --run-service'
    else:
        bin_path = f'"{target_exe}" --run-service'

    script = f'''
$name = "{SERVICE_NAME}"
$bin = '{bin_path}'
$svc = Get-Service -Name $name -ErrorAction SilentlyContinue
if ($null -ne $svc) {{
    Stop-Service -Name $name -Force -ErrorAction SilentlyContinue
    Set-Service -Name $name -StartupType Automatic -DisplayName "{SERVICE_DISPLAY_NAME}" -Description "{SERVICE_DESCRIPTION}" -ErrorAction SilentlyContinue
    Set-ItemProperty -Path "HKLM:\\System\\CurrentControlSet\\Services\\$name" -Name ImagePath -Value $bin -ErrorAction SilentlyContinue
}} else {{
    New-Service -Name $name -BinaryPathName $bin -StartupType Automatic -DisplayName "{SERVICE_DISPLAY_NAME}" -Description "{SERVICE_DESCRIPTION}" -ErrorAction SilentlyContinue
}}
Start-Service -Name $name -ErrorAction SilentlyContinue
'''
    res = subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
        capture_output=True,
        text=True,
        check=False,
    )
    if res.returncode == 0:
        print(f"Служба {SERVICE_NAME} успешно установлена и запущена.")
        return 0

    cmd_install = f'sc.exe create {SERVICE_NAME} binPath= "{bin_path}" start= auto DisplayName= "{SERVICE_DISPLAY_NAME}"'
    subprocess.run(["cmd.exe", "/c", cmd_install], check=False)
    subprocess.run(["cmd.exe", "/c", f"sc.exe start {SERVICE_NAME}"], check=False)
    return 0


def start_service() -> int:
    if not _is_admin():
        return _elevate_self(["--start"])
    subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", f'Start-Service "{SERVICE_NAME}" -ErrorAction SilentlyContinue; & cmd.exe /c "sc.exe start {SERVICE_NAME}" 2>$null'],
        check=False,
    )
    return 0


def stop_service() -> int:
    if not _is_admin():
        return _elevate_self(["--stop"])
    subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", f'Stop-Service "{SERVICE_NAME}" -Force -ErrorAction SilentlyContinue; & cmd.exe /c "sc.exe stop {SERVICE_NAME}" 2>$null'],
        check=False,
    )
    return 0


def uninstall_service() -> int:
    if not _is_admin():
        return _elevate_self(["--uninstall"])
    script = f'''
$name = "{SERVICE_NAME}"
Stop-Service -Name $name -Force -ErrorAction SilentlyContinue
& cmd.exe /c "sc.exe delete $name" 2>$null
'''
    subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script], check=False)
    print(f"Служба {SERVICE_NAME} удалена.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Pelmeni VPN Windows Service Helper")
    parser.add_argument("--install", action="store_true", help="Установить и запустить службу")
    parser.add_argument("--start", action="store_true", help="Запустить службу")
    parser.add_argument("--stop", action="store_true", help="Остановить службу")
    parser.add_argument("--uninstall", "--remove", action="store_true", help="Удалить службу")
    parser.add_argument("--run-worker", action="store_true", help="Запустить рабочий процесс IPC")
    parser.add_argument("--run-service", action="store_true", help="Запуск под диспетчером служб Windows")

    args, unknown = parser.parse_known_args()

    if args.install:
        return install_service()
    if args.uninstall:
        return uninstall_service()
    if args.start:
        return start_service()
    if args.stop:
        return stop_service()
    if args.run_worker:
        run_service_worker()
        return 0
    if args.run_service or not any(vars(args).values()):
        if PelmeniWindowsService is not None and "--run-worker" not in sys.argv:
            try:
                import win32serviceutil
                win32serviceutil.HandleCommandLine(PelmeniWindowsService)
                return 0
            except Exception:
                pass
        run_service_worker()
        return 0

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
