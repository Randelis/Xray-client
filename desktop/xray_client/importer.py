"""Turn user input into ProxyNodes: share links, or a subscription URL to fetch."""
from __future__ import annotations

import urllib.request

from .models import ProxyNode
from .parser import parse_configs

_USER_AGENT = "XrayClient/1.0"
_TIMEOUT_S = 15


def import_text(raw_input: str) -> list[ProxyNode]:
    text = raw_input.strip()
    if _is_subscription_url(text):
        text = _fetch(text)
    return parse_configs(text)


def _is_subscription_url(text: str) -> bool:
    low = text.lower()
    return (low.startswith("http://") or low.startswith("https://")) and not any(
        c.isspace() for c in text
    )


def _fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": _USER_AGENT})
    with urllib.request.urlopen(req, timeout=_TIMEOUT_S) as resp:
        if resp.status and resp.status >= 400:
            raise RuntimeError(f"Subscription fetch failed (HTTP {resp.status})")
        charset = resp.headers.get_content_charset() or "utf-8"
        return resp.read().decode(charset, "replace")
