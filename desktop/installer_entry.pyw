from __future__ import annotations

import sys

import installer


if "--install" in sys.argv:
    installer.install_application(True)
else:
    installer.main()
