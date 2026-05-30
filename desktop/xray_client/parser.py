"""Parse share links / subscriptions into ProxyNodes.

Mirrors the Android SubscriptionParser. Accepts vless://, vmess://, trojan://
links (one per line) or a base64 subscription body.
"""
from __future__ import annotations

import base64
import binascii
import json
import urllib.parse
from typing import Optional

from .models import ProxyNode

_COUNTRY_NAMES = {
    "united states": "US", "usa": "US", "america": "US",
    "japan": "JP", "tokyo": "JP", "germany": "DE", "frankfurt": "DE",
    "singapore": "SG", "hong kong": "HK", "hongkong": "HK",
    "united kingdom": "GB", "london": "GB", "netherlands": "NL",
    "france": "FR", "canada": "CA", "korea": "KR", "taiwan": "TW",
    "russia": "RU", "india": "IN", "australia": "AU", "turkey": "TR",
}


def parse_configs(raw_input: str) -> list[ProxyNode]:
    text = _maybe_base64_decode(raw_input.strip())
    nodes: list[ProxyNode] = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            if line.startswith("vless://"):
                node = _parse_vless(line)
            elif line.startswith("vmess://"):
                node = _parse_vmess(line)
            elif line.startswith("trojan://"):
                node = _parse_trojan(line)
            else:
                continue
        except Exception:
            continue
        if node and node.host and 1 <= node.port <= 65535:
            nodes.append(node)
    return nodes


# ── vless://uuid@host:port?query#remark ─────────────────────────────────────
def _parse_vless(link: str) -> ProxyNode:
    u = urllib.parse.urlsplit(link)
    q = _query(u.query)
    host = u.hostname or ""
    port = u.port or 0
    remark = urllib.parse.unquote(u.fragment) if u.fragment else host
    return ProxyNode(
        id=_stable_id("vless", host, port, u.username or "", remark),
        name=remark,
        country_code=_guess_country(remark),
        host=host,
        port=port,
        uuid=u.username or "",
        protocol="vless",
        flow=q.get("flow", ""),
        encryption=q.get("encryption") or "none",
        network=q.get("type") or "tcp",
        security=q.get("security") or "none",
        sni=q.get("sni") or q.get("host", ""),
        alpn=q.get("alpn", ""),
        fingerprint=q.get("fp", ""),
        public_key=q.get("pbk", ""),
        short_id=q.get("sid", ""),
        spider_x=q.get("spx", ""),
        path=q.get("path", ""),
        host_header=q.get("host", ""),
        service_name=q.get("serviceName", ""),
        header_type=q.get("headerType", ""),
    )


# ── vmess://<base64 json> ───────────────────────────────────────────────────
def _parse_vmess(link: str) -> ProxyNode:
    decoded = _b64(link[len("vmess://"):])
    if decoded is None:
        raise ValueError("bad vmess base64")
    o = json.loads(decoded)

    def s(key: str) -> str:
        v = o.get(key, "")
        return "" if v is None else str(v)

    def i(key: str) -> int:
        try:
            return int(o.get(key, 0) or 0)
        except (TypeError, ValueError):
            return 0

    host = s("add")
    port = i("port")
    remark = s("ps") or host
    tls = s("tls")
    net = s("net") or "tcp"
    return ProxyNode(
        id=_stable_id("vmess", host, port, s("id"), remark),
        name=remark,
        country_code=_guess_country(remark),
        host=host,
        port=port,
        uuid=s("id"),
        protocol="vmess",
        vmess_security=s("scy") or "auto",
        alter_id=i("aid"),
        network=net,
        security="none" if tls in ("", "none") else "tls",
        sni=s("sni") or s("host"),
        alpn=s("alpn"),
        fingerprint=s("fp"),
        path=s("path"),
        host_header=s("host"),
        service_name=s("path") if net == "grpc" else "",
        header_type=s("type"),
    )


# ── trojan://password@host:port?query#remark ────────────────────────────────
def _parse_trojan(link: str) -> ProxyNode:
    u = urllib.parse.urlsplit(link)
    q = _query(u.query)
    host = u.hostname or ""
    port = u.port or 0
    remark = urllib.parse.unquote(u.fragment) if u.fragment else host
    return ProxyNode(
        id=_stable_id("trojan", host, port, u.username or "", remark),
        name=remark,
        country_code=_guess_country(remark),
        host=host,
        port=port,
        uuid=u.username or "",        # trojan password
        protocol="trojan",
        network=q.get("type") or "tcp",
        security=q.get("security") or "tls",   # trojan is TLS by default
        sni=q.get("sni") or q.get("peer") or q.get("host", ""),
        alpn=q.get("alpn", ""),
        fingerprint=q.get("fp", ""),
        path=q.get("path", ""),
        host_header=q.get("host", ""),
        service_name=q.get("serviceName", ""),
    )


# ── helpers ─────────────────────────────────────────────────────────────────
def _query(raw: str) -> dict[str, str]:
    if not raw:
        return {}
    # keep_blank_values so an explicit empty param doesn't vanish
    return {k: v for k, v in urllib.parse.parse_qsl(raw, keep_blank_values=True)}


def _maybe_base64_decode(text: str) -> str:
    if "://" in text:
        return text
    return _b64(text) or text


def _b64(value: str) -> Optional[str]:
    cleaned = value.strip().replace("\n", "").replace("\r", "").replace(" ", "")
    if not cleaned:
        return None
    cleaned = cleaned.replace("-", "+").replace("_", "/")
    cleaned += "=" * (-len(cleaned) % 4)
    try:
        return base64.b64decode(cleaned).decode("utf-8", "replace")
    except (binascii.Error, ValueError):
        return None


def _stable_id(*parts) -> str:
    return str(abs(hash("|".join(str(p) for p in parts))) % (10 ** 12))


def _guess_country(remark: str) -> str:
    # 1) flag emoji (regional indicators) -> ASCII
    cps = [ord(c) for c in remark]
    for a, b in zip(cps, cps[1:]):
        if 0x1F1E6 <= a <= 0x1F1FF and 0x1F1E6 <= b <= 0x1F1FF:
            return chr(ord("A") + a - 0x1F1E6) + chr(ord("A") + b - 0x1F1E6)
    # 2) known country name
    low = remark.lower()
    for name, cc in _COUNTRY_NAMES.items():
        if name in low:
            return cc
    return ""


def flag_emoji(country_code: str) -> str:
    cc = country_code.upper()
    if len(cc) != 2 or not cc.isalpha():
        return "\U0001F3F3"  # white flag
    return "".join(chr(0x1F1E6 + (ord(c) - ord("A"))) for c in cc)
