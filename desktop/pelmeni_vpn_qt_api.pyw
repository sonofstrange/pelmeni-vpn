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

from PySide6 import QtQuick, QtQuickControls2  # noqa: F401
from PySide6.QtCore import QUrl
from PySide6.QtGui import QGuiApplication, QIcon
from PySide6.QtQml import QQmlApplicationEngine

from pelmeni_desktop.api_backend import DesktopApiBackend
from pelmeni_desktop.main import _self_test
from pelmeni_desktop.windows_proxy import restore_stale_proxy



def resource_path(relative: str) -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent)) / relative


def main() -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--restore-proxy", action="store_true")
    parser.add_argument("--install-service", action="store_true")
    parser.add_argument("--uninstall-service", action="store_true")
    parser.add_argument("--service-worker", action="store_true")
    parser.add_argument("--run-service", action="store_true")

    args, qt_args = parser.parse_known_args()

    if args.install_service:
        from pelmeni_desktop.service import install_service
        return install_service()
    if args.uninstall_service:
        from pelmeni_desktop.service import uninstall_service
        return uninstall_service()
    if args.service_worker or args.run_service:
        from pelmeni_desktop.service import run_service_worker
        run_service_worker()
        return 0
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
    backend = DesktopApiBackend()
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
