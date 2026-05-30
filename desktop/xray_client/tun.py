"""System-wide TUN mode: tun2socks + wintun, with route/DNS management.

Pipeline:  OS traffic -> wintun TUN device -> tun2socks -> SOCKS5 (xray) -> server

Routing is the delicate part:
  * a /32 *bypass* route pins each server IP to the real gateway, so xray's own
    connection to the server never re-enters the tunnel (no loop);
  * two /1 *catch-all* routes through the TUN gateway capture everything else,
    beating the existing default route by specificity (so it's left intact and
    restore is clean).

Requires admin and the tun2socks + wintun.dll binaries in bin/. Windows only.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Callable, Optional

from . import netutil
from .config_builder import SOCKS_PORT

_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
_SPLIT_METRIC = 5


class TunError(RuntimeError):
    pass


class TunManager:
    """Owns the tun2socks process and every route/DNS change, so teardown is
    exact and best-effort even after a partial start."""

    def __init__(self, log: Optional[Callable[[str], None]] = None):
        self._proc: Optional[subprocess.Popen] = None
        self._bypassed_ips: list[str] = []
        self._split_added = False
        self._active = False
        self._log = log or (lambda _m: None)

    @staticmethod
    def is_supported() -> bool:
        return os.name == "nt"

    @property
    def is_active(self) -> bool:
        return self._active

    # ── discovery ───────────────────────────────────────────────────────────
    @staticmethod
    def find_tun2socks() -> Optional[Path]:
        exe = "tun2socks.exe" if os.name == "nt" else "tun2socks"
        here = Path(__file__).resolve().parent
        for c in (here / "bin" / exe, here.parent / "bin" / exe,
                  Path(sys.argv[0]).resolve().parent / "bin" / exe):
            if c.is_file():
                return c
        found = shutil.which(exe)
        return Path(found) if found else None

    @staticmethod
    def has_wintun() -> bool:
        here = Path(__file__).resolve().parent
        return any((p / "wintun.dll").is_file() for p in (here / "bin", here.parent / "bin"))

    # ── lifecycle ───────────────────────────────────────────────────────────
    def start(self, server_ips: list[str]) -> None:
        if self._active:
            return
        if not self.is_supported():
            raise TunError("TUN mode is only supported on Windows.")
        binary = self.find_tun2socks()
        if binary is None:
            raise TunError("tun2socks not found — put tun2socks.exe in bin/ (see README).")
        if not self.has_wintun():
            raise TunError("wintun.dll not found — put it in bin/ (see README).")
        if not server_ips:
            raise TunError("Could not resolve the server address — check the node.")

        default = netutil.query_default_route()
        if default is None:
            raise TunError("Could not determine the current default gateway.")
        self._log(f"Default gateway {default.next_hop} (if {default.interface_index})")

        try:
            self._apply_bypass_routes(server_ips, default)
            self._launch_tun2socks(binary)
            self._wait_for_adapter()
            self._configure_adapter()
            self._apply_split_routes()
            self._active = True
            self._log("TUN mode active — all traffic routed through the tunnel.")
        except Exception:
            self.stop()  # best-effort rollback of whatever was applied
            raise

    def stop(self) -> None:
        # Remove routes first so traffic falls back to the real default immediately.
        if self._split_added:
            for cmd in netutil.delete_split_default_routes():
                self._run(cmd)
            self._split_added = False
        for ip in self._bypassed_ips:
            self._run(netutil.delete_host_route(ip))
        self._bypassed_ips = []

        if self._proc is not None:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._proc.kill()
            self._proc = None
        self._active = False

    # ── internals ───────────────────────────────────────────────────────────
    def _apply_bypass_routes(self, server_ips, default) -> None:
        for ip in server_ips:
            self._run(netutil.server_bypass_route(ip, default.next_hop, default.interface_index))
            self._bypassed_ips.append(ip)

    def _launch_tun2socks(self, binary: Path) -> None:
        cmd = [
            str(binary),
            "-device", f"tun://{netutil.TUN_NAME}",
            "-proxy", f"socks5://127.0.0.1:{SOCKS_PORT}",
            "-loglevel", "warning",
        ]
        self._log("Starting tun2socks…")
        self._proc = subprocess.Popen(
            cmd, cwd=str(binary.parent),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=_NO_WINDOW,
        )

    def _wait_for_adapter(self, timeout_s: float = 8.0) -> None:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            if self._proc is not None and self._proc.poll() is not None:
                raise TunError("tun2socks exited immediately — check the binary and wintun.dll.")
            if netutil.adapter_exists():
                return
            time.sleep(0.4)
        raise TunError(f"TUN adapter '{netutil.TUN_NAME}' did not come up in time.")

    def _configure_adapter(self) -> None:
        self._log("Configuring TUN adapter (IP / DNS / MTU)…")
        for cmd in netutil.configure_tun_commands():
            self._run(cmd)

    def _apply_split_routes(self) -> None:
        for cmd in netutil.split_default_routes(netutil.TUN_GATEWAY, _SPLIT_METRIC):
            self._run(cmd)
        self._split_added = True

    def _run(self, cmd: list[str]) -> None:
        try:
            subprocess.run(
                cmd, capture_output=True, text=True, timeout=20, creationflags=_NO_WINDOW
            )
        except (OSError, subprocess.SubprocessError) as e:
            self._log(f"command failed: {' '.join(cmd)} ({e})")
