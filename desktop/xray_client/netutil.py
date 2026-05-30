"""Network helpers for TUN mode.

The pure functions here (IP checks, default-route JSON parsing, route/netsh
command construction, default-route splitting) carry the tricky logic and are
unit-tested. The side-effecting wrappers shell out to Windows tools.
"""
from __future__ import annotations

import ipaddress
import json
import socket
import subprocess
from typing import NamedTuple, Optional

# TUN adapter identity (kept in one place so the GUI/README can reference it).
TUN_NAME = "XrayTun"
TUN_ADDR = "10.10.10.2"
TUN_MASK = "255.255.255.0"
TUN_GATEWAY = "10.10.10.1"
TUN_MTU = 1400
TUN_DNS = "1.1.1.1"

_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


class DefaultRoute(NamedTuple):
    next_hop: str
    interface_index: int
    interface_alias: str


# ── pure helpers ────────────────────────────────────────────────────────────
def is_ipv4(host: str) -> bool:
    try:
        return isinstance(ipaddress.ip_address(host), ipaddress.IPv4Address)
    except ValueError:
        return False


def parse_default_route(json_text: str) -> Optional[DefaultRoute]:
    """Parse `Get-NetRoute -DestinationPrefix 0.0.0.0/0 | ConvertTo-Json` output.

    PowerShell emits a bare object for one row, or a list for many. We pick the
    lowest-metric active row.
    """
    try:
        data = json.loads(json_text)
    except (ValueError, TypeError):
        return None
    rows = data if isinstance(data, list) else [data]
    rows = [r for r in rows if isinstance(r, dict) and r.get("NextHop")]
    if not rows:
        return None
    rows.sort(key=lambda r: _as_int(r.get("RouteMetric"), 9999))
    best = rows[0]
    try:
        return DefaultRoute(
            next_hop=str(best["NextHop"]),
            interface_index=_as_int(best.get("InterfaceIndex"), 0),
            interface_alias=str(best.get("InterfaceAlias", "")),
        )
    except (KeyError, TypeError):
        return None


def split_default_routes(gateway: str, metric: int) -> list[list[str]]:
    """Two /1 halves that beat any existing 0.0.0.0/0 by specificity — so the
    original default route is left intact for a clean restore."""
    return [
        ["route", "add", "0.0.0.0", "mask", "128.0.0.0", gateway, "metric", str(metric)],
        ["route", "add", "128.0.0.0", "mask", "128.0.0.0", gateway, "metric", str(metric)],
    ]


def delete_split_default_routes() -> list[list[str]]:
    return [
        ["route", "delete", "0.0.0.0", "mask", "128.0.0.0"],
        ["route", "delete", "128.0.0.0", "mask", "128.0.0.0"],
    ]


def server_bypass_route(ip: str, gateway: str, interface_index: int, metric: int = 1) -> list[str]:
    """Pin one server IP to the physical gateway so the tunnel's own underlying
    connection never re-enters the TUN."""
    cmd = ["route", "add", ip, "mask", "255.255.255.255", gateway, "metric", str(metric)]
    if interface_index:
        cmd += ["if", str(interface_index)]
    return cmd


def delete_host_route(ip: str) -> list[str]:
    return ["route", "delete", ip, "mask", "255.255.255.255"]


def configure_tun_commands() -> list[list[str]]:
    """Assign IP / DNS / MTU to the TUN adapter once tun2socks has created it."""
    return [
        ["netsh", "interface", "ip", "set", "address",
         f"name={TUN_NAME}", "static", TUN_ADDR, TUN_MASK],
        ["netsh", "interface", "ip", "set", "dns",
         f"name={TUN_NAME}", "static", TUN_DNS],
        ["netsh", "interface", "ipv4", "set", "subinterface",
         TUN_NAME, f"mtu={TUN_MTU}", "store=active"],
    ]


# ── side-effecting ──────────────────────────────────────────────────────────
def resolve_ipv4(host: str) -> list[str]:
    """Resolve a host to its IPv4 addresses (must be called BEFORE routing is
    hijacked). If host is already an IPv4 literal it's returned as-is."""
    if is_ipv4(host):
        return [host]
    try:
        infos = socket.getaddrinfo(host, None, socket.AF_INET)
    except socket.gaierror:
        return []
    seen: list[str] = []
    for info in infos:
        ip = info[4][0]
        if ip not in seen:
            seen.append(ip)
    return seen


def query_default_route(run=None) -> Optional[DefaultRoute]:
    run = run or _run_capture
    out = run([
        "powershell", "-NoProfile", "-Command",
        "Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | "
        "Select-Object NextHop,InterfaceIndex,InterfaceAlias,RouteMetric | ConvertTo-Json",
    ])
    return parse_default_route(out) if out else None


def adapter_exists(name: str = TUN_NAME, run=None) -> bool:
    run = run or _run_capture
    out = run([
        "powershell", "-NoProfile", "-Command",
        f"if (Get-NetAdapter -Name '{name}' -ErrorAction SilentlyContinue) {{'yes'}}",
    ])
    return bool(out) and "yes" in out.lower()


def _run_capture(cmd: list[str]) -> str:
    try:
        res = subprocess.run(
            cmd, capture_output=True, text=True, timeout=20, creationflags=_NO_WINDOW
        )
        return res.stdout or ""
    except (OSError, subprocess.SubprocessError):
        return ""


def _as_int(value, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default
