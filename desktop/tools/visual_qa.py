from __future__ import annotations

import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
os.environ.setdefault("QT_QUICK_CONTROLS_STYLE", "Basic")
os.environ["APPDATA"] = str(ROOT / "test-appdata")

from PySide6.QtCore import QMetaObject, QTimer, QUrl, qInstallMessageHandler
from PySide6.QtGui import QGuiApplication
from PySide6.QtQml import QQmlApplicationEngine

from pelmeni_desktop.full_backend import FullDesktopBackend


def main() -> int:
    warnings: list[str] = []
    qInstallMessageHandler(lambda _kind, _context, message: warnings.append(message))
    app = QGuiApplication(sys.argv)
    backend = FullDesktopBackend()
    engine = QQmlApplicationEngine()
    engine.rootContext().setContextProperty("backend", backend)
    engine.rootContext().setContextProperty("logoSource", QUrl())
    engine.load(QUrl.fromLocalFile(str(ROOT / "qml" / "Main.qml")))
    if not engine.rootObjects():
        print("\n".join(warnings))
        return 2

    window = engine.rootObjects()[0]
    api = window.findChild(object, "apiLayer")
    output = ROOT / "visual-qa"
    output.mkdir(exist_ok=True)
    steps = [
        ("01-home.png", lambda: window.setProperty("currentPage", 0)),
        ("02-people.png", lambda: window.setProperty("currentPage", 1)),
        ("03-settings.png", lambda: window.setProperty("currentPage", 2)),
        ("04-add.png", lambda: window.setProperty("currentPage", 3)),
        ("05-servers-empty.png", lambda: (api.setProperty("profiles", []), api.setProperty("pageName", "profiles"))),
        ("06-servers.png", lambda: (api.setProperty("profiles", [{"id": "1", "name": "Нидерланды", "host": "31.76.110.227", "port": 22, "active": True}]), api.setProperty("pageName", "profiles"))),
        ("07-public.png", lambda: (api.setProperty("publicServers", [{"name": "Нидерланды free", "host": "31.76.110.227", "location": "Нидерланды", "daily_mb": 2048, "monthly_mb": 51200, "speed_mbps": 10, "days": "∞"}]), api.setProperty("pageName", "public"))),
        ("08-people-user.png", lambda: (window.setProperty("currentPage", 1), api.setProperty("pageName", ""), api.setProperty("people", [{"label": "Друг", "login": "pel_friend", "expires": "2026-12-31", "daily_mb": 2048, "monthly_mb": 51200, "speed_mbps": 10, "day_bytes": 123456, "month_bytes": 987654, "blocked": False, "expired": False, "access_code": "PEL1-demo"}]))),
        ("09-split.png", lambda: api.setProperty("pageName", "split")),
        ("10-app-split.png", lambda: api.setProperty("pageName", "appsplit")),
        ("11-tls.png", lambda: api.setProperty("pageName", "tls")),
        ("12-migration.png", lambda: api.setProperty("pageName", "migration")),
        ("13-publish.png", lambda: api.setProperty("pageName", "publish")),
        ("14-server-editor.png", lambda: QMetaObject.invokeMethod(window, "openNewServerEditor")),
    ]
    index = 0

    def advance() -> None:
        nonlocal index
        if index >= len(steps):
            relevant = [line for line in warnings if "failed" in line.lower() or "error" in line.lower() or "reference" in line.lower()]
            print(f"screens={len(steps)} size={window.width()}x{window.height()} warnings={len(relevant)}")
            if relevant:
                print("\n".join(relevant))
            backend.shutdown()
            app.quit()
            return
        name, action = steps[index]
        action()
        QTimer.singleShot(120, lambda: capture(name))

    def capture(name: str) -> None:
        nonlocal index
        image = app.primaryScreen().grabWindow(window.winId(), 0, 0, window.width(), window.height())
        if image.width() != 380 or image.height() != 680:
            raise RuntimeError(f"wrong capture size {image.width()}x{image.height()}")
        image.save(str(output / name))
        index += 1
        QTimer.singleShot(40, advance)

    QTimer.singleShot(100, advance)
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
