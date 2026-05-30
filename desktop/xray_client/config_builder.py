"""Build an xray-core JSON config for a node — desktop twin of AdaptiveRoutingEngine.

Local-proxy mode: SOCKS5 + HTTP inbounds on 127.0.0.1. No geo assets required.
"""
from __future__ import annotations

from .models import ProxyNode

SOCKS_PORT = 10808
HTTP_PORT = 10809


def build_config(
    node: ProxyNode,
    socks_port: int = SOCKS_PORT,
    http_port: int = HTTP_PORT,
    pin_address: str | None = None,
) -> dict:
    """Build the xray config.

    pin_address: when set (TUN mode), xray connects to this literal IP instead of
    resolving node.host itself. SNI / Host headers still use the original
    hostname, so TLS validates. This guarantees the underlying connection goes to
    the exact IP we add a bypass route for — preventing a routing loop.
    """
    return {
        "log": {"loglevel": "warning"},
        "inbounds": [
            {
                "tag": "socks-in",
                "listen": "127.0.0.1",
                "port": socks_port,
                "protocol": "socks",
                "settings": {"auth": "noauth", "udp": True},
                "sniffing": _sniffing(),
            },
            {
                "tag": "http-in",
                "listen": "127.0.0.1",
                "port": http_port,
                "protocol": "http",
                "sniffing": _sniffing(),
            },
        ],
        "outbounds": [
            _proxy_outbound(node, pin_address),
            {"tag": "direct-out", "protocol": "freedom", "settings": {"domainStrategy": "UseIPv4"}},
            {"tag": "block-out", "protocol": "blackhole"},
        ],
        "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": [
                {
                    "type": "field",
                    "outboundTag": "direct-out",
                    "ip": [
                        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                        "127.0.0.0/8", "::1/128", "fc00::/7", "fe80::/10",
                    ],
                }
            ],
        },
        "dns": {
            "queryStrategy": "UseIPv4",
            "servers": ["https://1.1.1.1/dns-query", "8.8.8.8"],
        },
    }


def _sniffing() -> dict:
    return {"enabled": True, "destOverride": ["http", "tls", "quic"], "routeOnly": False}


def _proxy_outbound(node: ProxyNode, pin_address: str | None = None) -> dict:
    out = {
        "tag": "proxy-out",
        "protocol": node.protocol,
        "settings": _outbound_settings(node, pin_address),
    }
    stream = _stream_settings(node)
    if stream is not None:
        out["streamSettings"] = stream
    return out


def _outbound_settings(node: ProxyNode, pin_address: str | None = None) -> dict:
    address = pin_address or node.host
    if node.protocol == "trojan":
        server = {"address": address, "port": node.port, "password": node.uuid}
        if node.flow:
            server["flow"] = node.flow
        return {"servers": [server]}

    # vless / vmess
    user: dict = {"id": node.uuid}
    if node.protocol == "vless":
        user["encryption"] = node.encryption or "none"
        if node.flow:
            user["flow"] = node.flow
    else:  # vmess
        user["alterId"] = node.alter_id
        user["security"] = node.vmess_security or "auto"
    return {"vnext": [{"address": address, "port": node.port, "users": [user]}]}


def _stream_settings(node: ProxyNode):
    net = node.network or "tcp"
    sec = node.security or "none"
    if net == "tcp" and sec == "none" and not node.header_type:
        return None

    s: dict = {"network": net, "security": sec}

    if sec == "tls":
        tls = {"serverName": node.sni or node.host, "allowInsecure": False}
        if node.alpn:
            tls["alpn"] = [a.strip() for a in node.alpn.split(",") if a.strip()]
        if node.fingerprint:
            tls["fingerprint"] = node.fingerprint
        s["tlsSettings"] = tls
    elif sec == "reality":
        reality = {
            "serverName": node.sni or node.host,
            "fingerprint": node.fingerprint or "chrome",
            "publicKey": node.public_key,
        }
        if node.short_id:
            reality["shortId"] = node.short_id
        if node.spider_x:
            reality["spiderX"] = node.spider_x
        s["realitySettings"] = reality

    if net == "ws":
        ws: dict = {}
        if node.path:
            ws["path"] = node.path
        if node.host_header:
            ws["headers"] = {"Host": node.host_header}
        s["wsSettings"] = ws
    elif net == "grpc":
        s["grpcSettings"] = {"serviceName": node.service_name or node.path}
    elif net in ("h2", "http"):
        http: dict = {}
        if node.path:
            http["path"] = node.path
        if node.host_header:
            http["host"] = [node.host_header]
        s["httpSettings"] = http
    elif net == "tcp" and node.header_type == "http":
        header: dict = {"type": "http"}
        if node.host_header or node.path:
            request: dict = {}
            if node.path:
                request["path"] = [node.path]
            if node.host_header:
                request["headers"] = {"Host": [node.host_header]}
            header["request"] = request
        s["tcpSettings"] = {"header": header}

    return s
