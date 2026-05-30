"""Owns the xray-core process lifecycle — desktop twin of CoreManager."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
from collections import deque
from enum import Enum, auto
from pathlib import Path
from typing import Callable, Optional


class State(Enum):
    IDLE = auto()
    STARTING = auto()
    RUNNING = auto()
    STOPPING = auto()
    ERROR = auto()


_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # Windows: hide console


def _runtime_dir() -> Path:
    """Where xray's generated config (server address + credentials) is written.
    Kept next to the app's data — portable, and out of the shared system temp."""
    from . import storage
    try:
        d = storage.DATA_DIR / "run"
        d.mkdir(parents=True, exist_ok=True)
        return d
    except OSError:
        d = Path(tempfile.gettempdir()) / "xray-client"
        d.mkdir(parents=True, exist_ok=True)
        return d


class XrayCore:
    """Starts/stops the bundled (or PATH) xray executable with a generated config."""

    def __init__(self, on_state: Optional[Callable[[State, str], None]] = None):
        self._proc: Optional[subprocess.Popen] = None
        self._monitor: Optional[threading.Thread] = None
        self._log_tail: deque[str] = deque(maxlen=200)
        self._config_path: Optional[Path] = None
        self._lock = threading.Lock()
        self.state = State.IDLE
        self._on_state = on_state

    # ── discovery ───────────────────────────────────────────────────────────
    @staticmethod
    def find_binary() -> Optional[Path]:
        """Look for xray next to the app (bin/), then on PATH."""
        exe = "xray.exe" if os.name == "nt" else "xray"
        here = Path(__file__).resolve().parent
        candidates = [
            here / "bin" / exe,
            here.parent / "bin" / exe,
            Path(sys.argv[0]).resolve().parent / "bin" / exe,
        ]
        for c in candidates:
            if c.is_file():
                return c
        found = shutil.which(exe) or shutil.which("xray")
        return Path(found) if found else None

    @property
    def is_running(self) -> bool:
        return self.state == State.RUNNING

    @property
    def log_tail(self) -> str:
        return "".join(self._log_tail)

    # ── lifecycle ───────────────────────────────────────────────────────────
    def start(self, config: dict) -> None:
        with self._lock:
            if self.state in (State.RUNNING, State.STARTING):
                return
            self._set_state(State.STARTING)
            binary = self.find_binary()
            if binary is None:
                self._set_state(
                    State.ERROR,
                    "xray executable not found. Put xray.exe in the 'bin' folder "
                    "or on your PATH (see README).",
                )
                return

            cfg_dir = _runtime_dir()
            self._config_path = cfg_dir / "config.json"
            self._config_path.write_text(json.dumps(config, indent=2), encoding="utf-8")

            try:
                self._proc = subprocess.Popen(
                    [str(binary), "run", "-config", str(self._config_path)],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    cwd=str(cfg_dir),
                    creationflags=_NO_WINDOW,
                    text=True,
                    bufsize=1,
                )
            except OSError as e:
                self._set_state(State.ERROR, f"Failed to launch xray: {e}")
                return

            self._log_tail.clear()
            self._set_state(State.RUNNING)
            self._monitor = threading.Thread(target=self._watch, args=(self._proc,), daemon=True)
            self._monitor.start()

    def stop(self) -> None:
        with self._lock:
            proc = self._proc
            if proc is None:
                return
            self._set_state(State.STOPPING)
            proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
        with self._lock:
            self._proc = None
            self._set_state(State.IDLE)

    def restart(self, config: dict) -> None:
        self.stop()
        self.start(config)

    # ── internals ───────────────────────────────────────────────────────────
    def _watch(self, proc: subprocess.Popen) -> None:
        if proc.stdout is not None:
            for line in proc.stdout:
                self._log_tail.append(line)
        code = proc.wait()
        with self._lock:
            # Only report error if this is still the live process and we didn't stop it.
            if self._proc is proc and self.state == State.RUNNING:
                tail = "".join(list(self._log_tail)[-6:]).strip()
                msg = f"xray exited unexpectedly (code={code})."
                if tail:
                    msg += f"\n{tail}"
                self._proc = None
                self._set_state(State.ERROR, msg)

    def _set_state(self, state: State, message: str = "") -> None:
        self.state = state
        if self._on_state is not None:
            self._on_state(state, message)
