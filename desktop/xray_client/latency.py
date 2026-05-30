"""TCP-connect latency probing with a weighted moving average (WMA).

Twin of the Android LatencyViewModel ranking logic.
"""
from __future__ import annotations

import socket
import time
from collections import defaultdict, deque
from concurrent.futures import ThreadPoolExecutor
from typing import Optional

from .models import ProxyNode, RankedNode

WMA_WINDOW = 5
CONNECT_TIMEOUT_S = 3.0


class LatencyProber:
    def __init__(self) -> None:
        self._windows: dict[str, deque[int]] = defaultdict(lambda: deque(maxlen=WMA_WINDOW))

    def probe_all(self, nodes: list[ProxyNode]) -> list[RankedNode]:
        if not nodes:
            return []
        with ThreadPoolExecutor(max_workers=min(16, len(nodes))) as pool:
            rtts = list(pool.map(lambda n: (n, _tcp_rtt(n.host, n.port)), nodes))

        ranked: list[RankedNode] = []
        for node, rtt in rtts:
            window = self._windows[node.id]
            if rtt is not None:
                window.append(rtt)
            ranked.append(
                RankedNode(
                    node=node,
                    smoothed_latency_ms=_wma(window),
                    last_raw_latency_ms=rtt if rtt is not None else -1,
                    sample_count=len(window),
                )
            )
        ranked.sort(key=lambda r: (not r.is_reachable, r.smoothed_latency_ms))
        return ranked


def _tcp_rtt(host: str, port: int) -> Optional[int]:
    try:
        t0 = time.monotonic()
        with socket.create_connection((host, port), timeout=CONNECT_TIMEOUT_S):
            return int((time.monotonic() - t0) * 1000)
    except OSError:
        return None


def _wma(samples: deque[int]) -> float:
    if not samples:
        return float("inf")
    weighted_sum = 0.0
    total_weight = 0
    for i, v in enumerate(samples):  # oldest -> newest
        w = i + 1
        weighted_sum += w * v
        total_weight += w
    return weighted_sum / total_weight
