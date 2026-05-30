"""Application logic: ties together core, parser, latency, storage, system proxy.

Exposes Qt signals so the GUI stays a thin view. Background work (probing,
importing, connecting) runs on a thread pool; results are marshalled back to the
UI thread via signals.
"""
from __future__ import annotations

from typing import Optional

from PySide6.QtCore import QObject, QRunnable, QThreadPool, Signal

from . import importer, storage, system_proxy
from .config_builder import HTTP_PORT, SOCKS_PORT, build_config
from .core import State, XrayCore
from .latency import LatencyProber
from .models import ProxyNode, RankedNode


class _WorkerSignals(QObject):
    result = Signal(object)
    error = Signal(object)


class _Worker(QRunnable):
    def __init__(self, fn, *args):
        super().__init__()
        self.fn, self.args = fn, args
        self.signals = _WorkerSignals()

    def run(self):
        try:
            self.signals.result.emit(self.fn(*self.args))
        except Exception as e:  # noqa: BLE001 - surfaced to the user
            self.signals.error.emit(e)


class Controller(QObject):
    state_changed = Signal(object, str)     # State, message
    servers_updated = Signal(list)          # list[RankedNode]
    message = Signal(str)
    busy = Signal(bool)

    def __init__(self) -> None:
        super().__init__()
        self._pool = QThreadPool.globalInstance()
        self._prober = LatencyProber()
        self._core = XrayCore(on_state=self._on_core_state)
        self._nodes: list[ProxyNode] = storage.load_nodes()
        self._ranked: list[RankedNode] = [RankedNode(n) for n in self._nodes]
        self._selected_id: Optional[str] = None
        self._use_system_proxy = system_proxy.is_supported()
        self._proxy_active = False

    # ── properties ───────────────────────────────────────────────────────────
    @property
    def ranked(self) -> list[RankedNode]:
        return self._ranked

    @property
    def state(self) -> State:
        return self._core.state

    @property
    def system_proxy_supported(self) -> bool:
        return system_proxy.is_supported()

    @property
    def use_system_proxy(self) -> bool:
        return self._use_system_proxy

    def set_use_system_proxy(self, value: bool) -> None:
        self._use_system_proxy = value

    def set_selected(self, node_id: Optional[str]) -> None:
        self._selected_id = node_id

    def proxy_hint(self) -> str:
        return f"SOCKS5 127.0.0.1:{SOCKS_PORT}   •   HTTP 127.0.0.1:{HTTP_PORT}"

    # ── initial load ─────────────────────────────────────────────────────────
    def start_initial_probe(self) -> None:
        self.servers_updated.emit(self._ranked)
        if self._nodes:
            self.refresh()

    # ── latency refresh ──────────────────────────────────────────────────────
    def refresh(self) -> None:
        self.busy.emit(True)
        w = _Worker(self._prober.probe_all, list(self._nodes))
        w.signals.result.connect(self._on_probe_done)
        w.signals.error.connect(lambda e: (self.busy.emit(False), self.message.emit(f"Probe failed: {e}")))
        self._pool.start(w)

    def _on_probe_done(self, ranked: list[RankedNode]) -> None:
        self._ranked = ranked
        self.busy.emit(False)
        self.servers_updated.emit(self._ranked)

    # ── import ───────────────────────────────────────────────────────────────
    def import_text(self, raw: str) -> None:
        self.busy.emit(True)
        self.message.emit("Importing…")
        w = _Worker(importer.import_text, raw)
        w.signals.result.connect(self._on_import_done)
        w.signals.error.connect(self._on_import_error)
        self._pool.start(w)

    def _on_import_done(self, nodes: list[ProxyNode]) -> None:
        if not nodes:
            self.busy.emit(False)
            self.message.emit("No servers found in the input")
            return
        self._nodes = storage.merge_nodes(self._nodes, nodes)
        storage.save_nodes(self._nodes)
        self.message.emit(f"Imported {len(nodes)} server(s)")
        self.refresh()

    def _on_import_error(self, e: Exception) -> None:
        self.busy.emit(False)
        self.message.emit(f"Import failed: {e}")

    # ── remove ───────────────────────────────────────────────────────────────
    def remove(self, node_id: str) -> None:
        self._nodes = [n for n in self._nodes if n.id != node_id]
        self._ranked = [r for r in self._ranked if r.node.id != node_id]
        storage.save_nodes(self._nodes)
        self.servers_updated.emit(self._ranked)

    # ── connect / disconnect ─────────────────────────────────────────────────
    def toggle_connection(self) -> None:
        if self._core.is_running or self._core.state == State.STARTING:
            self._run_bg(self._disconnect)
        else:
            node = self._pick_node()
            if node is None:
                self.message.emit("No server selected — import and pick one first")
                return
            self._run_bg(self._connect, node)

    def _connect(self, node: ProxyNode) -> None:
        self._core.start(build_config(node))
        if self._core.is_running and self._use_system_proxy and system_proxy.is_supported():
            self._proxy_active = system_proxy.enable(port=HTTP_PORT)

    def _disconnect(self) -> None:
        self._clear_proxy()
        self._core.stop()

    def _clear_proxy(self) -> None:
        if self._proxy_active:
            system_proxy.disable()
            self._proxy_active = False

    def _pick_node(self) -> Optional[ProxyNode]:
        if self._selected_id:
            for r in self._ranked:
                if r.node.id == self._selected_id:
                    return r.node
        return self._ranked[0].node if self._ranked else None

    # ── core state bridge (runs on monitor thread) ───────────────────────────
    def _on_core_state(self, state: State, message: str) -> None:
        if state == State.ERROR:
            self._clear_proxy()
        self.state_changed.emit(state, message)

    def _run_bg(self, fn, *args) -> None:
        w = _Worker(fn, *args)
        w.signals.error.connect(lambda e: self.message.emit(f"Error: {e}"))
        self._pool.start(w)

    def shutdown(self) -> None:
        self._clear_proxy()
        self._core.stop()
