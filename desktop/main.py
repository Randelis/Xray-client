"""Entry point for the Xray Client desktop app."""
from __future__ import annotations

import sys

from PySide6.QtWidgets import QApplication

from xray_client.controller import Controller
from xray_client.gui import MainWindow


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("Xray Client")
    controller = Controller()
    window = MainWindow(controller)
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
