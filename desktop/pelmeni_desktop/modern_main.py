from __future__ import annotations

import argparse

import customtkinter as ctk

from .main import _enable_dpi_awareness, _self_test
from .modern_ui import PelmeniDesktopApp
from .windows_proxy import restore_stale_proxy


def main() -> None:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--restore-proxy", action="store_true")
    args, _ = parser.parse_known_args()
    if args.self_test:
        _self_test()
        return
    if args.restore_proxy:
        restore_stale_proxy()
        return

    _enable_dpi_awareness()
    ctk.set_appearance_mode("dark")
    ctk.set_default_color_theme("dark-blue")
    root = ctk.CTk()
    PelmeniDesktopApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
