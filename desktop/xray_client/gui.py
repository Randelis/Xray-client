"""PySide6 desktop UI for the Xray client."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (
    QAbstractItemView, QApplication, QComboBox, QDialog, QDialogButtonBox, QFrame,
    QHBoxLayout, QHeaderView, QLabel, QMainWindow, QMessageBox, QPlainTextEdit,
    QPushButton, QTableWidget, QTableWidgetItem, QVBoxLayout, QWidget,
)

from . import elevate
from .controller import MODE_MANUAL, MODE_PROXY, MODE_TUN, Controller
from .core import State
from .models import RankedNode
from .parser import flag_emoji

_MODES = [
    ("System proxy (browsers & WinINET apps)", MODE_PROXY),
    ("TUN — all apps, system-wide", MODE_TUN),
    ("Manual (SOCKS5 / HTTP only)", MODE_MANUAL),
]

_DOT = {
    State.IDLE:     ("#4A4A4A", "Disconnected"),
    State.STARTING: ("#FF8A1F", "Connecting…"),
    State.RUNNING:  ("#00E676", "Connected"),
    State.STOPPING: ("#FF8A1F", "Disconnecting…"),
    State.ERROR:    ("#FF3D00", "Error"),
}

_STYLE = """
QWidget { background: #121212; color: #E6E6E6; font-size: 13px; }
QPushButton {
    background: #1F1F1F; border: 1px solid #2E2E2E; border-radius: 8px;
    padding: 8px 14px;
}
QPushButton:hover { background: #2A2A2A; }
QPushButton#connect {
    background: #00C853; color: #07210F; font-weight: 600; border: none;
    border-radius: 22px; padding: 12px 28px; font-size: 15px;
}
QPushButton#connect[connected="true"] { background: #B00020; color: #FFFFFF; }
QTableWidget { background: #161616; border: 1px solid #242424; border-radius: 8px; gridline-color: #242424; }
QHeaderView::section { background: #1B1B1B; border: none; padding: 6px; color: #9E9E9E; }
QPlainTextEdit { background: #161616; border: 1px solid #2E2E2E; border-radius: 8px; padding: 8px; }
"""


def _ping_color(ms: float) -> QColor:
    if ms < 100:
        return QColor("#00E676")
    if ms < 300:
        return QColor("#FFB300")
    return QColor("#FF3D00")


class ImportDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Import servers")
        self.resize(560, 360)
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel(
            "Paste a VLESS / VMess / Trojan link (one per line) or a subscription URL:"
        ))
        self.edit = QPlainTextEdit()
        self.edit.setPlaceholderText("vless://…\nvmess://…\nhttps://your-subscription")
        layout.addWidget(self.edit)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("Import")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def text(self) -> str:
        return self.edit.toPlainText().strip()


class MainWindow(QMainWindow):
    def __init__(self, controller: Controller):
        super().__init__()
        self.c = controller
        self.setWindowTitle("Xray Client")
        self.resize(720, 540)
        self.setStyleSheet(_STYLE)

        root = QWidget()
        self.setCentralWidget(root)
        outer = QVBoxLayout(root)
        outer.setContentsMargins(16, 16, 16, 8)
        outer.setSpacing(12)

        # ── header ────────────────────────────────────────────────────────
        header = QHBoxLayout()
        title = QLabel("Xray Client")
        title.setStyleSheet("font-size: 20px; font-weight: 700;")
        self.dot = QFrame()
        self.dot.setFixedSize(14, 14)
        self.status_label = QLabel("Disconnected")
        self.status_label.setStyleSheet("color: #9E9E9E;")
        self.connect_btn = QPushButton("Connect")
        self.connect_btn.setObjectName("connect")
        self.connect_btn.clicked.connect(self._on_connect_clicked)

        header.addWidget(title)
        header.addSpacing(12)
        header.addWidget(self.dot)
        header.addWidget(self.status_label)
        header.addStretch(1)
        header.addWidget(self.connect_btn)
        outer.addLayout(header)

        # ── server table ──────────────────────────────────────────────────
        self.table = QTableWidget(0, 3)
        self.table.setHorizontalHeaderLabels(["Server", "Country", "Ping"])
        self.table.verticalHeader().setVisible(False)
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SingleSelection)
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        self.table.horizontalHeader().setSectionResizeMode(1, QHeaderView.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(2, QHeaderView.ResizeToContents)
        self.table.itemSelectionChanged.connect(self._on_selection)
        self.table.itemDoubleClicked.connect(lambda _: self._on_connect_clicked())
        outer.addWidget(self.table, 1)

        # ── action row ────────────────────────────────────────────────────
        actions = QHBoxLayout()
        self.import_btn = QPushButton("Import")
        self.import_btn.clicked.connect(self._open_import)
        self.refresh_btn = QPushButton("Refresh ping")
        self.refresh_btn.clicked.connect(self.c.refresh)
        self.remove_btn = QPushButton("Remove")
        self.remove_btn.clicked.connect(self._remove_selected)

        self.mode_combo = QComboBox()
        for label, value in _MODES:
            self.mode_combo.addItem(label, value)
        for i, (_, value) in enumerate(_MODES):
            if value == self.c.mode:
                self.mode_combo.setCurrentIndex(i)
        if not self.c.tun_supported:  # TUN is Windows-only
            idx = next(i for i, (_, v) in enumerate(_MODES) if v == MODE_TUN)
            self.mode_combo.model().item(idx).setEnabled(False)
        self.mode_combo.currentIndexChanged.connect(self._on_mode_change)

        actions.addWidget(self.import_btn)
        actions.addWidget(self.refresh_btn)
        actions.addWidget(self.remove_btn)
        actions.addStretch(1)
        actions.addWidget(QLabel("Mode:"))
        actions.addWidget(self.mode_combo)
        outer.addLayout(actions)

        # ── footer ────────────────────────────────────────────────────────
        self.hint = QLabel(self.c.proxy_hint())
        self.hint.setStyleSheet("color: #6E6E6E; font-size: 11px;")
        outer.addWidget(self.hint)
        self.statusBar().setStyleSheet("color: #9E9E9E;")

        # ── wire controller ───────────────────────────────────────────────
        self.c.state_changed.connect(self._on_state)
        self.c.servers_updated.connect(self._on_servers)
        self.c.message.connect(self._on_message)
        self.c.busy.connect(self._on_busy)

        self._on_state(State.IDLE, "")
        self.c.start_initial_probe()

    # ── controller callbacks ──────────────────────────────────────────────
    def _on_state(self, state: State, message: str):
        color, text = _DOT.get(state, ("#4A4A4A", "Disconnected"))
        self.dot.setStyleSheet(f"background: {color}; border-radius: 7px;")
        self.status_label.setText(text)
        connected = state in (State.RUNNING, State.STARTING, State.STOPPING)
        self.connect_btn.setText("Disconnect" if connected else "Connect")
        self.connect_btn.setProperty("connected", "true" if connected else "false")
        self.connect_btn.style().unpolish(self.connect_btn)
        self.connect_btn.style().polish(self.connect_btn)
        if message:
            self.statusBar().showMessage(message, 8000)

    def _on_servers(self, ranked: list[RankedNode]):
        selected = self._selected_id()
        self.table.setRowCount(len(ranked))
        for row, r in enumerate(ranked):
            name = f"{flag_emoji(r.node.country_code)}  {r.node.name}"
            name_item = QTableWidgetItem(name)
            name_item.setData(Qt.UserRole, r.node.id)
            self.table.setItem(row, 0, name_item)
            self.table.setItem(row, 1, QTableWidgetItem(r.node.country_code or "—"))

            if r.is_reachable:
                ms = int(r.smoothed_latency_ms)
                ping_item = QTableWidgetItem(f"{ms} ms")
                ping_item.setForeground(_ping_color(ms))
            else:
                ping_item = QTableWidgetItem("—")
                ping_item.setForeground(QColor("#6E6E6E"))
            self.table.setItem(row, 2, ping_item)

            if r.node.id == selected:
                self.table.selectRow(row)

    def _on_message(self, msg: str):
        self.statusBar().showMessage(msg, 8000)

    def _on_busy(self, busy: bool):
        self.refresh_btn.setEnabled(not busy)
        self.refresh_btn.setText("Refreshing…" if busy else "Refresh ping")

    # ── ui helpers ────────────────────────────────────────────────────────
    def _selected_id(self):
        items = self.table.selectedItems()
        if not items:
            return None
        return self.table.item(items[0].row(), 0).data(Qt.UserRole)

    def _on_selection(self):
        self.c.set_selected(self._selected_id())

    def _on_mode_change(self, index: int):
        self.c.set_mode(self.mode_combo.itemData(index))

    def _on_connect_clicked(self):
        # When connecting in TUN mode, make sure we're elevated first.
        connecting = self.c.state not in (State.RUNNING, State.STARTING, State.STOPPING)
        if connecting and self.c.mode == MODE_TUN and not elevate.is_admin():
            if not self._prompt_elevation():
                return
        self.c.toggle_connection()

    def _prompt_elevation(self) -> bool:
        box = QMessageBox(self)
        box.setIcon(QMessageBox.Question)
        box.setWindowTitle("Administrator required")
        box.setText(
            "TUN mode changes system routes, which needs administrator rights.\n\n"
            "Relaunch Xray Client as administrator now?"
        )
        box.setStandardButtons(QMessageBox.Yes | QMessageBox.No)
        if box.exec() != QMessageBox.Yes:
            return False
        if elevate.relaunch_as_admin():
            self.c.shutdown()
            QApplication.quit()
            return False  # the elevated instance takes over
        QMessageBox.warning(self, "Xray Client", "Could not elevate. Run the app as administrator.")
        return False

    def _remove_selected(self):
        node_id = self._selected_id()
        if node_id:
            self.c.remove(node_id)

    def _open_import(self):
        dlg = ImportDialog(self)
        if dlg.exec() == QDialog.Accepted and dlg.text():
            self.c.import_text(dlg.text())

    def closeEvent(self, event):
        self.c.shutdown()
        super().closeEvent(event)
