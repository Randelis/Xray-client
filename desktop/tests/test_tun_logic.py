"""Unit tests for the pure TUN / routing logic (no Windows or admin needed)."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from xray_client import netutil
from xray_client.config_builder import build_config
from xray_client.models import ProxyNode


def test_is_ipv4():
    assert netutil.is_ipv4("1.2.3.4")
    assert not netutil.is_ipv4("example.com")
    assert not netutil.is_ipv4("::1")
    assert not netutil.is_ipv4("999.1.1.1")


def test_parse_default_route_single_object():
    txt = json.dumps({"NextHop": "192.168.1.1", "InterfaceIndex": 12,
                      "InterfaceAlias": "Wi-Fi", "RouteMetric": 25})
    r = netutil.parse_default_route(txt)
    assert r is not None
    assert r.next_hop == "192.168.1.1"
    assert r.interface_index == 12
    assert r.interface_alias == "Wi-Fi"


def test_parse_default_route_list_picks_lowest_metric():
    txt = json.dumps([
        {"NextHop": "10.0.0.1", "InterfaceIndex": 5, "InterfaceAlias": "Eth", "RouteMetric": 50},
        {"NextHop": "192.168.1.1", "InterfaceIndex": 12, "InterfaceAlias": "Wi-Fi", "RouteMetric": 10},
    ])
    r = netutil.parse_default_route(txt)
    assert r.next_hop == "192.168.1.1"
    assert r.interface_index == 12


def test_parse_default_route_garbage():
    assert netutil.parse_default_route("not json") is None
    assert netutil.parse_default_route("[]") is None
    assert netutil.parse_default_route(json.dumps({"InterfaceIndex": 1})) is None  # no NextHop


def test_split_default_routes_cover_whole_space():
    cmds = netutil.split_default_routes("10.10.10.1", 5)
    assert cmds[0][:5] == ["route", "add", "0.0.0.0", "mask", "128.0.0.0"]
    assert cmds[1][:5] == ["route", "add", "128.0.0.0", "mask", "128.0.0.0"]
    assert "10.10.10.1" in cmds[0] and "10.10.10.1" in cmds[1]
    assert cmds[0][-2:] == ["metric", "5"]


def test_server_bypass_route_includes_interface():
    cmd = netutil.server_bypass_route("203.0.113.7", "192.168.1.1", 12)
    assert cmd[:3] == ["route", "add", "203.0.113.7"]
    assert "255.255.255.255" in cmd
    assert "192.168.1.1" in cmd
    assert cmd[-2:] == ["if", "12"]


def test_server_bypass_route_omits_interface_when_zero():
    cmd = netutil.server_bypass_route("203.0.113.7", "192.168.1.1", 0)
    assert "if" not in cmd


def test_configure_tun_commands_use_tun_name():
    cmds = netutil.configure_tun_commands()
    flat = [tok for cmd in cmds for tok in cmd]
    assert f"name={netutil.TUN_NAME}" in flat
    assert netutil.TUN_ADDR in flat
    assert netutil.TUN_DNS in flat
    assert any(f"mtu={netutil.TUN_MTU}" in cmd for cmd in cmds)


def test_resolve_ipv4_passthrough_for_literal():
    assert netutil.resolve_ipv4("8.8.8.8") == ["8.8.8.8"]


def test_pin_address_overrides_connect_ip_but_keeps_sni():
    node = ProxyNode(id="1", name="n", host="real.example.com", port=443, uuid="u",
                     protocol="vless", security="tls", sni="real.example.com")
    cfg = build_config(node, pin_address="203.0.113.7")
    ob = cfg["outbounds"][0]
    # connects to the pinned IP ...
    assert ob["settings"]["vnext"][0]["address"] == "203.0.113.7"
    # ... but TLS still validates against the original hostname
    assert ob["streamSettings"]["tlsSettings"]["serverName"] == "real.example.com"


def test_pin_address_trojan():
    node = ProxyNode(id="2", name="n", host="real.example.com", port=443, uuid="pw",
                     protocol="trojan", security="tls", sni="real.example.com")
    cfg = build_config(node, pin_address="198.51.100.9")
    ob = cfg["outbounds"][0]
    assert ob["settings"]["servers"][0]["address"] == "198.51.100.9"
    assert ob["streamSettings"]["tlsSettings"]["serverName"] == "real.example.com"


if __name__ == "__main__":
    funcs = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for f in funcs:
        f()
        print(f"  ok  {f.__name__}")
    print(f"\nALL {len(funcs)} TUN-LOGIC TESTS PASSED")
