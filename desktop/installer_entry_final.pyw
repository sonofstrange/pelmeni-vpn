from __future__ import annotations

import ctypes
import os
import shutil
import sys
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


if "--finish-uninstall" in sys.argv:
    finish_uninstall()
elif "--install" in sys.argv:
    installer.install_application(True)
else:
    installer.main()
