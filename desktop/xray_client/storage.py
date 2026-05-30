"""Persist nodes. Portable-first: data lives next to the .exe so the app can be
carried around (USB stick, any folder) and leaves no trace on the machine. Falls
back to the per-user config dir if the executable's folder isn't writable."""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

from .models import ProxyNode


def _is_writable(d: Path) -> bool:
    try:
        d.mkdir(parents=True, exist_ok=True)
        probe = d / ".write-test"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink()
        return True
    except OSError:
        return False


def _appdata_dir() -> Path:
    if os.name == "nt":
        base = Path(os.environ.get("APPDATA", Path.home()))
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return base / "XrayClient"


def _data_dir() -> Path:
    # 1) explicit override (handy for testing / custom locations)
    override = os.environ.get("XRAYCLIENT_DATA")
    if override:
        d = Path(override).expanduser()
        if _is_writable(d):
            return d
    # 2) portable: a folder next to the executable (PyInstaller onefile/onedir)
    if getattr(sys, "frozen", False):
        portable = Path(sys.executable).resolve().parent / "XrayClient-Data"
        if _is_writable(portable):
            return portable
    # 3) fallback: per-user config dir (e.g. exe sits in read-only Program Files)
    appdata = _appdata_dir()
    _is_writable(appdata)
    return appdata


DATA_DIR = _data_dir()
_NODES_FILE = DATA_DIR / "nodes.json"


def load_nodes() -> list[ProxyNode]:
    if not _NODES_FILE.is_file():
        return []
    try:
        data = json.loads(_NODES_FILE.read_text(encoding="utf-8"))
        return [ProxyNode.from_dict(d) for d in data]
    except (ValueError, OSError, TypeError):
        return []  # tolerant: corrupt data -> empty


def save_nodes(nodes: list[ProxyNode]) -> None:
    try:
        _NODES_FILE.write_text(
            json.dumps([n.to_dict() for n in nodes], indent=2),
            encoding="utf-8",
        )
    except OSError:
        pass


def merge_nodes(existing: list[ProxyNode], incoming: list[ProxyNode]) -> list[ProxyNode]:
    """Add incoming, replacing any existing node with the same id (dedupe)."""
    incoming_ids = {n.id for n in incoming}
    return [n for n in existing if n.id not in incoming_ids] + incoming
