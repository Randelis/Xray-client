"""PySide6 desktop UI for the Xray client."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QAbstractItemView, QApplication, QComboBox, QDialog, QDialogButtonBox, QFrame,
    QHBoxLayout, QHeaderView, QLabel, QMainWindow, QMessageBox, QPlainTextEdit,
    QPushButton, QStackedWidget, QTableWidget, QTableWidgetItem, QVBoxLayout, QWidget,
)

from . import elevate, theme
from .controller import MODE_MANUAL, MODE_PROXY, MODE_TUN, Controller
from .core import State
from .models import RankedNode
from .parser import flag_emoji
from .widgets import NameDelegate, PingPillDelegate, PowerButton, StatusDot, make_shadow

_MODES = [
    ("System proxy (browsers & WinINET apps)", MODE_PROXY),
    ("TUN — all apps, system-wide", MODE_TUN),
    ("Manual (SOCKS5 / HTTP only)", MODE_MANUAL),
]

_CONNECTING_STATES = (State.RUNNING, State.STARTING, State.STOPPING)


class ImportDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Import servers")
        self.resize(560, 360)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 20, 20, 20)
        layout.setSpacing(12)
        heading = QLabel("Add servers")
        heading.setObjectName("title")
        layout.addWidget(heading)
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
        self._ranked: list[RankedNode] = []
        self.setWindowTitle("Xray Client")
        self.resize(860, 680)
        self.setMinimumSize(720, 600)
        self.setStyleSheet(theme.STYLESHEET)

        root = QWidget()
        root.setObjectName("root")
        self.setCentralWidget(root)
        outer = QVBoxLayout(root)
        outer.setContentsMargins(18, 18, 18, 12)
        outer.setSpacing(14)

        outer.addWidget(self._build_hero())
        outer.addWidget(self._build_server_card(), 1)
        outer.addLayout(self._build_controls())

        self.hint = QLabel(self.c.proxy_hint())
        self.hint.setObjectName("hint")
        self.hint.setAlignment(Qt.AlignHCenter)
        outer.addWidget(self.hint)

        # ── wire controller ───────────────────────────────────────────────
        self.c.state_changed.connect(self._on_state)
        self.c.servers_updated.connect(self._on_servers)
        self.c.message.connect(self._on_message)
        self.c.busy.connect(self._on_busy)

        self._on_state(State.IDLE, "")
        self.c.start_initial_probe()

    # ── builders ──────────────────────────────────────────────────────────
    def _build_hero(self) -> QFrame:
        hero = QFrame()
        hero.setObjectName("hero")
        make_shadow(hero, blur=34, dy=12, alpha=150)
        lay = QVBoxLayout(hero)
        lay.setContentsMargins(24, 18, 24, 20)
        lay.setSpacing(8)

        top = QHBoxLayout()
        title_box = QVBoxLayout()
        title_box.setSpacing(0)
        title = QLabel("Xray Client")
        title.setObjectName("title")
        subtitle = QLabel("Secure proxy tunnel")
        subtitle.setObjectName("subtitle")
        title_box.addWidget(title)
        title_box.addWidget(subtitle)
        top.addLayout(title_box)
        top.addStretch(1)
        self.dot = StatusDot()
        self.status_label = QLabel("Disconnected")
        self.status_label.setObjectName("status")
        top.addWidget(self.dot)
        top.addSpacing(6)
        top.addWidget(self.status_label)
        lay.addLayout(top)

        self.connect_btn = PowerButton()
        self.connect_btn.clicked.connect(self._on_connect_clicked)
        center = QHBoxLayout()
        center.addStretch(1)
        center.addWidget(self.connect_btn)
        center.addStretch(1)
        lay.addSpacing(6)
        lay.addLayout(center)

        self.action_label = QLabel("Tap to connect")
        self.action_label.setObjectName("action")
        self.action_label.setAlignment(Qt.AlignHCenter)
        self.active_label = QLabel("No server selected")
        self.active_label.setObjectName("active")
        self.active_label.setAlignment(Qt.AlignHCenter)
        lay.addWidget(self.action_label)
        lay.addWidget(self.active_label)
        return hero

    def _build_server_card(self) -> QFrame:
        card = QFrame()
        card.setObjectName("card")
        make_shadow(card, blur=30, dy=10, alpha=130)
        lay = QVBoxLayout(card)
        lay.setContentsMargins(14, 12, 14, 14)

        self.stack = QStackedWidget()

        placeholder = QWidget()
        ph = QVBoxLayout(placeholder)
        empty = QLabel("No servers yet — click Import to add one.")
        empty.setObjectName("empty")
        empty.setAlignment(Qt.AlignCenter)
        ph.addStretch(1)
        ph.addWidget(empty)
        ph.addStretch(1)

        self.table = self._build_table()
        self.stack.addWidget(placeholder)   # index 0
        self.stack.addWidget(self.table)    # index 1
        lay.addWidget(self.stack)
        return card

    def _build_table(self) -> QTableWidget:
        table = QTableWidget(0, 3)
        table.setHorizontalHeaderLabels(["SERVER", "COUNTRY", "PING"])
        table.verticalHeader().setVisible(False)
        table.verticalHeader().setDefaultSectionSize(56)
        table.setShowGrid(False)
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        table.setSelectionMode(QAbstractItemView.SingleSelection)
        table.setFocusPolicy(Qt.NoFocus)
        table.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        header = table.horizontalHeader()
        header.setHighlightSections(False)
        header.setSectionResizeMode(0, QHeaderView.Stretch)
        header.setSectionResizeMode(1, QHeaderView.Fixed)
        header.setSectionResizeMode(2, QHeaderView.Fixed)
        table.setColumnWidth(1, 92)
        table.setColumnWidth(2, 128)

        self._name_delegate = NameDelegate(table)
        self._ping_delegate = PingPillDelegate(table)
        table.setItemDelegateForColumn(0, self._name_delegate)
        table.setItemDelegateForColumn(2, self._ping_delegate)

        table.itemSelectionChanged.connect(self._on_selection)
        table.itemDoubleClicked.connect(lambda _: self._on_connect_clicked())
        return table

    def _build_controls(self) -> QHBoxLayout:
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

        mode_cap = QLabel("Mode")
        mode_cap.setObjectName("modecap")
        actions.addWidget(self.import_btn)
        actions.addWidget(self.refresh_btn)
        actions.addWidget(self.remove_btn)
        actions.addStretch(1)
        actions.addWidget(mode_cap)
        actions.addWidget(self.mode_combo)
        return actions

    # ── controller callbacks ──────────────────────────────────────────────
    def _on_state(self, state: State, message: str):
        color, label = theme.STATUS.get(state.name, (theme.TEXT_DIM, "Disconnected"))
        pulse = state in (State.STARTING, State.STOPPING)
        self.dot.set_state(color, pulse)
        self.status_label.setText(label)
        self.status_label.setStyleSheet(f"color: {color};")
        self.connect_btn.set_status(state.name)
        connected = state in _CONNECTING_STATES
        self.connect_btn.setText("Disconnect" if connected else "Connect")
        self.action_label.setText(theme.ACTION.get(state.name, "Tap to connect"))
        if message:
            self.statusBar().showMessage(message, 8000)

    def _on_servers(self, ranked: list[RankedNode]):
        self._ranked = ranked
        selected = self._selected_id()
        self.table.setRowCount(len(ranked))
        for row, r in enumerate(ranked):
            name = f"{flag_emoji(r.node.country_code)}  {r.node.name}"
            name_item = QTableWidgetItem(name)
            name_item.setData(Qt.UserRole, r.node.id)
            name_item.setData(NameDelegate.HOST_ROLE, f"{r.node.host}:{r.node.port}")
            self.table.setItem(row, 0, name_item)

            country = QTableWidgetItem(r.node.country_code or "—")
            country.setTextAlignment(Qt.AlignCenter)
            self.table.setItem(row, 1, country)

            ms = int(r.smoothed_latency_ms) if r.is_reachable else None
            self.table.setItem(row, 2, QTableWidgetItem(f"{ms} ms" if ms is not None else "—"))

            if r.node.id == selected:
                self.table.selectRow(row)

        self.stack.setCurrentIndex(1 if ranked else 0)
        self._update_active()

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

    def _update_active(self):
        node_id = self._selected_id()
        for r in self._ranked:
            if r.node.id == node_id:
                self.active_label.setText(f"{flag_emoji(r.node.country_code)}  {r.node.name}")
                return
        self.active_label.setText("No server selected")

    def _on_selection(self):
        self.c.set_selected(self._selected_id())
        self._update_active()

    def _on_mode_change(self, index: int):
        self.c.set_mode(self.mode_combo.itemData(index))

    def _on_connect_clicked(self):
        # When connecting in TUN mode, make sure we're elevated first.
        connecting = self.c.state not in _CONNECTING_STATES
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
