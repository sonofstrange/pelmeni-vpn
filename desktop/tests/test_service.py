from __future__ import annotations

import sys
import threading
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from pelmeni_desktop.service import run_service_worker, _remove_endpoint
from pelmeni_desktop.service_client import ServiceClient


class ServiceIpcTests(unittest.TestCase):
    def setUp(self) -> None:
        _remove_endpoint()

    def tearDown(self) -> None:
        _remove_endpoint()

    def test_service_worker_and_client_communication(self) -> None:
        stop_event = threading.Event()
        worker_thread = threading.Thread(
            target=run_service_worker,
            args=(0, stop_event),
            daemon=True,
        )
        worker_thread.start()
        time.sleep(0.4)

        client = None
        try:
            self.assertTrue(ServiceClient.is_service_available())
            client = ServiceClient()
            self.assertTrue(client.connect())
            res = client._send_cmd("ping")
            self.assertEqual(res.get("status"), "ok")
        finally:
            if client:
                client.close()
            stop_event.set()
            worker_thread.join(timeout=2.0)

    def test_service_entry_module_and_metadata(self) -> None:
        import service_entry
        self.assertTrue(hasattr(service_entry, "SERVICE_NAME"))
        self.assertTrue(hasattr(service_entry, "SERVICE_DISPLAY_NAME"))
        self.assertTrue(hasattr(service_entry, "SERVICE_DESCRIPTION"))
        self.assertTrue(callable(service_entry.install_service))
        self.assertTrue(callable(service_entry.uninstall_service))
        self.assertTrue(callable(service_entry.start_service))
        self.assertTrue(callable(service_entry.stop_service))
