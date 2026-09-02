from __future__ import annotations

import threading

from PySide6.QtCore import Signal, Slot

from .qt_backend import DesktopBackend



class QtDesktopBackend(DesktopBackend):
    """Queues worker results back to Qt's GUI thread through signals."""

    connectionSucceeded = Signal()
    connectionFailed = Signal(str)
    stopFinished = Signal()

    def __init__(self) -> None:
        super().__init__()
        self.connectionSucceeded.connect(self._connected)
        self.connectionFailed.connect(self._connection_failed)
        self.stopFinished.connect(self._stopped)

    def _start_connection(self) -> None:
        if self._connecting or self.manager.is_active():
            return
        self._intentional_stop = False
        self._connecting = True
        self._remember_modes()
        self._set_status()

        def worker() -> None:
            try:
                self.manager.start(self.config["profile"], self._ask_host_key, self._proxy)
                if self._vpn:
                    self._enable_vpn()
                self.connectionSucceeded.emit()
            except Exception as error:
                self._disable_vpn()
                self.manager.stop()
                self.connectionFailed.emit(str(error))

        threading.Thread(target=worker, name="pelmeni-qt-connect", daemon=True).start()

    @Slot()
    def stopConnection(self) -> None:
        if self._stopping:
            return
        self._intentional_stop = True
        self._stopping = True
        self._set_status()

        def worker() -> None:
            try:
                self._disable_vpn()
            finally:
                self._commit_traffic()
                self.manager.stop()
                self.stopFinished.emit()

        threading.Thread(target=worker, name="pelmeni-qt-stop", daemon=True).start()
