from __future__ import annotations

import os
import sys
import winreg
from pathlib import Path


RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
VALUE_NAME = "Pelmeni VPN"


def set_start_on_boot(enabled: bool) -> None:
    if os.name != "nt":
        return
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
        if enabled:
            executable = Path(sys.executable).resolve()
            if getattr(sys, "frozen", False):
                command = f'"{executable}"'
            else:
                entry = Path(__file__).resolve().parents[1] / "pelmeni_vpn_qt_api.pyw"
                command = f'"{executable}" "{entry}"'
            winreg.SetValueEx(key, VALUE_NAME, 0, winreg.REG_SZ, command)
        else:
            try:
                winreg.DeleteValue(key, VALUE_NAME)
            except FileNotFoundError:
                pass
