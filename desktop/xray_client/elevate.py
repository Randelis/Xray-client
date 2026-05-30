"""Admin-privilege helpers. TUN mode needs them to edit routes / adapters."""
from __future__ import annotations

import os
import sys


def is_admin() -> bool:
    if os.name != "nt":
        try:
            return os.geteuid() == 0  # type: ignore[attr-defined]
        except AttributeError:
            return False
    try:
        import ctypes

        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def relaunch_as_admin() -> bool:
    """Re-launch the current app via the UAC elevation prompt. Returns True if the
    elevated process was started (the caller should then exit)."""
    if os.name != "nt":
        return False
    try:
        import ctypes

        params = " ".join(f'"{a}"' for a in sys.argv)
        # ShellExecuteW(hwnd, "runas", file, params, dir, SW_SHOWNORMAL)
        rc = ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, params, None, 1)
        return rc > 32
    except Exception:
        return False
