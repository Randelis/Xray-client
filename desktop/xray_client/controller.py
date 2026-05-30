"""Application logic: ties together core, parser, latency, storage, system proxy.

Exposes Qt signals so the GUI stays a thin view. Background work (probing,
importing, connecting) runs on a thread pool; results are marshalled back to the
UI thread via signals.
"""
from __future__ import annotations

from typing import Optional

from PySide6.QtCore import QObject, QRunnable, QThreadPool, Signal

from . import importer, netutil, storage, system_proxy
from .config_builder import HTTP_PORT, SOCKS_PORT, build_config
from .core import State, XrayCore
from .latency import LatencyProber
from .models import ProxyNode, RankedNode
from .tun import TunError, TunManager

# Connection modes
MODE_MANUAL = "manual"   # just expose SOCKS5/HTTP; user configures apps
MODE_PROXY = "proxy"     # set the Windows system proxy
MODE_TUN = "tun"         # system-wide TUN: every app routed


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
        self._tun = TunManager(log=lambda m: self.message.emit(m))
        self._mode = MODE_PROXY if system_proxy.is_supported() else MODE_MANUAL
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
    def tun_supported(self) -> bool:
        return TunManager.is_supported()

    @property
    def mode(self) -> str:
        return self._mode

    def set_mode(self, mode: str) -> None:
        self._mode = mode

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
        # In TUN mode, resolve the server up front (before routing is hijacked) and
        # pin xray to that exact IP, so its underlying connection bypasses the TUN.
        pin_ip = None
        server_ips: list[str] = []
        if self._mode == MODE_TUN:
            server_ips = netutil.resolve_ipv4(node.host)
            if not server_ips:
                self.state_changed.emit(State.ERROR, f"Could not resolve server {node.host}.")
                return
            pin_ip = server_ips[0]

        self._core.start(build_config(node, pin_address=pin_ip))
        if not self._core.is_running:
            return  # core already reported the error via its state callback

        if self._mode == MODE_PROXY and system_proxy.is_supported():
            self._proxy_active = system_proxy.enable(port=HTTP_PORT)
        elif self._mode == MODE_TUN:
            try:
                self._tun.start(server_ips)
            except TunError as e:
                self._core.stop()
                self.state_changed.emit(State.ERROR, str(e))

    def _disconnect(self) -> None:
        self._tear_down_routing()
        self._core.stop()

    def _tear_down_routing(self) -> None:
        if self._tun.is_active:
            self._tun.stop()
        self._clear_proxy()

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
            self._tear_down_routing()
        self.state_changed.emit(state, message)

    def _run_bg(self, fn, *args) -> None:
        w = _Worker(fn, *args)
        w.signals.error.connect(lambda e: self.message.emit(f"Error: {e}"))
        self._pool.start(w)

    def shutdown(self) -> None:
        self._tear_down_routing()
        self._core.stop()
