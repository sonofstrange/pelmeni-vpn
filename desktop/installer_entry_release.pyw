from __future__ import annotations

import ctypes
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import installer


def finish_uninstall() -> None:
    index = sys.argv.index("--finish-uninstall")
    target = installer._validated_install_dir(Path(sys.argv[index + 1]))
    parent_pid = int(sys.argv[index + 2])
    for _ in range(100):
        try:
            os.kill(parent_pid, 0)
        except OSError:
            break
        time.sleep(0.1)
    shutil.rmtree(target, ignore_errors=False)
    if "--silent" not in sys.argv:
        ctypes.windll.user32.MessageBoxW(
            None,
            "Пельмени VPN Desktop удалён. Настройки серверов сохранены в AppData.",
            installer.PRODUCT_NAME,
            0x40,
        )
    ctypes.windll.kernel32.MoveFileExW(str(Path(sys.executable)), None, 4)


def silent_uninstall() -> None:
    target = installer._validated_install_dir(installer.install_dir())
    if installer.app_is_running():
        raise SystemExit(2)
    app = target / installer.APP_EXE
    if app.exists():
        subprocess.run(
            [str(app), "--restore-proxy"],
            startupinfo=installer._startupinfo(),
            timeout=20,
            check=False,
        )
    installer.remove_shortcuts()
    installer.remove_uninstall_registry()
    helper = Path(tempfile.gettempdir()) / f"PelmeniVPN-Uninstall-{os.getpid()}.exe"
    shutil.copy2(Path(sys.executable), helper)
    subprocess.Popen(
        [
            str(helper),
            "--finish-uninstall",
            str(target),
            str(os.getpid()),
            "--silent",
        ],
        startupinfo=installer._startupinfo(),
    )


if "--finish-uninstall" in sys.argv:
    finish_uninstall()
elif "--install" in sys.argv:
    installer.install_application(True)
elif "--uninstall" in sys.argv and "--silent" in sys.argv:
    silent_uninstall()
else:
    installer.main()
