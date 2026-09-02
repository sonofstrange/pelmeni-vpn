from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

pyside_dir = Path(sys.prefix) / "Lib" / "site-packages" / "PySide6"
if pyside_dir.exists():
    try:
        os.add_dll_directory(str(pyside_dir))
    except (AttributeError, OSError):
        pass
    os.environ["PATH"] = str(pyside_dir) + os.pathsep + os.environ.get("PATH", "")

from PySide6 import QtQuick, QtQuickControls2  # noqa: F401 - required QML plugins
from PySide6.QtCore import QUrl
from PySide6.QtGui import QGuiApplication, QIcon
from PySide6.QtQml import QQmlApplicationEngine

from pelmeni_desktop.main import _self_test
from pelmeni_desktop.qt_runtime_backend import QtDesktopBackend
from pelmeni_desktop.windows_proxy import restore_stale_proxy


def resource_path(relative: str) -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent)) / relative


def main() -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--restore-proxy", action="store_true")
    args, qt_args = parser.parse_known_args()
    if args.self_test:
        _self_test()
        return 0
    if args.restore_proxy:
        restore_stale_proxy()
        return 0

    os.environ.setdefault("QT_QUICK_CONTROLS_STYLE", "Basic")
    app = QGuiApplication([sys.argv[0], *qt_args])
    app.setApplicationName("Пельмени VPN")
    app.setOrganizationName("Pelmeni VPN")
    icon = resource_path("assets/pelmeni_icon.png")
    if icon.exists():
        app.setWindowIcon(QIcon(str(icon)))
    backend = QtDesktopBackend()
    engine = QQmlApplicationEngine()
    engine.rootContext().setContextProperty("backend", backend)
    engine.rootContext().setContextProperty("logoSource", QUrl.fromLocalFile(str(icon)))
    engine.load(QUrl.fromLocalFile(str(resource_path("qml/Main.qml"))))
    if not engine.rootObjects():
        return 2
    app.aboutToQuit.connect(backend.shutdown)
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
