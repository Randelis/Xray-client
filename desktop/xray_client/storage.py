"""Persist nodes to a JSON file in the user's config directory."""
from __future__ import annotations

import json
import os
from pathlib import Path

from .models import ProxyNode


def _config_dir() -> Path:
    if os.name == "nt":
        base = Path(os.environ.get("APPDATA", Path.home()))
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    d = base / "XrayClient"
    d.mkdir(parents=True, exist_ok=True)
    return d


_NODES_FILE = _config_dir() / "nodes.json"


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
