"""Domain model for a proxy server — the desktop twin of the Android ProxyNode."""
from __future__ import annotations

from dataclasses import dataclass, field, asdict


@dataclass
class ProxyNode:
    id: str
    name: str
    host: str
    port: int
    uuid: str                      # vless/vmess id, or trojan password
    country_code: str = ""
    protocol: str = "vless"        # vless | vmess | trojan

    # vless / vmess user options
    flow: str = ""
    encryption: str = "none"       # vless
    vmess_security: str = "auto"   # vmess cipher (scy)
    alter_id: int = 0

    # stream / transport
    network: str = "tcp"           # tcp | ws | grpc | h2 | quic
    security: str = "none"         # none | tls | reality
    sni: str = ""
    alpn: str = ""
    fingerprint: str = ""
    public_key: str = ""           # reality pbk
    short_id: str = ""             # reality sid
    spider_x: str = ""             # reality spx
    path: str = ""                 # ws / h2 path
    host_header: str = ""          # ws / h2 Host header
    service_name: str = ""         # grpc
    header_type: str = ""          # tcp header type

    def to_dict(self) -> dict:
        return asdict(self)

    @staticmethod
    def from_dict(d: dict) -> "ProxyNode":
        # Tolerant: ignore unknown keys, fill missing with defaults.
        known = ProxyNode.__dataclass_fields__.keys()
        return ProxyNode(**{k: v for k, v in d.items() if k in known})


@dataclass
class RankedNode:
    node: ProxyNode
    smoothed_latency_ms: float = float("inf")
    last_raw_latency_ms: int = -1
    sample_count: int = 0

    @property
    def is_reachable(self) -> bool:
        return self.sample_count > 0
