"""Windows system proxy toggle (WinINET registry + refresh).

Points the OS HTTP/HTTPS proxy at xray's local HTTP inbound. No-op on non-Windows
so the app still runs cross-platform (you can configure apps manually there).
"""
from __future__ import annotations

import os

_IS_WINDOWS = os.name == "nt"
_INTERNET_SETTINGS = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings"

# Bypass local + common private ranges so LAN/loopback don't go through the proxy.
_BYPASS = "localhost;127.*;10.*;172.16.*;192.168.*;<local>"


def is_supported() -> bool:
    return _IS_WINDOWS


def enable(host: str = "127.0.0.1", port: int = 10809) -> bool:
    if not _IS_WINDOWS:
        return False
    import winreg

    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _INTERNET_SETTINGS, 0, winreg.KEY_WRITE) as key:
        winreg.SetValueEx(key, "ProxyEnable", 0, winreg.REG_DWORD, 1)
        winreg.SetValueEx(key, "ProxyServer", 0, winreg.REG_SZ, f"{host}:{port}")
        winreg.SetValueEx(key, "ProxyOverride", 0, winreg.REG_SZ, _BYPASS)
    _refresh()
    return True


def disable() -> bool:
    if not _IS_WINDOWS:
        return False
    import winreg

    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _INTERNET_SETTINGS, 0, winreg.KEY_WRITE) as key:
        winreg.SetValueEx(key, "ProxyEnable", 0, winreg.REG_DWORD, 0)
    _refresh()
    return True


def _refresh() -> None:
    """Tell WinINET to reload settings without a reboot/logoff."""
    try:
        import ctypes

        internet_option_settings_changed = 39
        internet_option_refresh = 37
        wininet = ctypes.windll.wininet
        wininet.InternetSetOptionW(0, internet_option_settings_changed, 0, 0)
        wininet.InternetSetOptionW(0, internet_option_refresh, 0, 0)
    except Exception:
        pass
