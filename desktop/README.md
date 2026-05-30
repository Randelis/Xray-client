# Xray Client — Desktop (Windows)

A PC version of the Android Xray client. It runs **xray-core** as a local
SOCKS5 / HTTP proxy and lets you import VLESS / VMess / Trojan links or a
subscription URL, probe server latency, and connect with one click. Optionally
it sets the **Windows system proxy** so apps that honor it (browsers, etc.) go
through the tunnel — no admin rights required.

> Built with Python + PySide6. It mirrors the Android app's config/parsing logic.

## Features

- Import a single `vless://` / `vmess://` / `trojan://` link, many at once
  (one per line), or a **subscription URL** (base64 bodies are auto-decoded).
- Server list with live TCP-RTT ping (weighted moving average), sorted fastest first.
- One-click connect/disconnect; the fastest (or selected) server is used.
- Full VLESS/VMess/Trojan outbound generation incl. **TLS / Reality** and
  **ws / grpc / h2 / tcp-http** transports.
- Optional Windows system-proxy toggle (points HTTP/HTTPS at `127.0.0.1:10809`).
- Servers persist between runs (`%APPDATA%\XrayClient\nodes.json`).

## Setup

1. **Install Python 3.10+** (3.11+ recommended).

2. **Install dependencies:**
   ```powershell
   cd desktop
   pip install -r requirements.txt
   ```

3. **Provide the xray-core executable.** Download the release for your OS from
   <https://github.com/XTLS/Xray-core/releases> and place the binary here:
   ```
   desktop/xray_client/bin/xray.exe      (Windows)
   desktop/xray_client/bin/xray          (Linux/macOS)
   ```
   Alternatively, put `xray` on your system `PATH`.

4. **Run:**
   ```powershell
   python main.py
   ```

## Usage

1. Click **Import**, paste your VLESS link(s) or subscription URL, click **Import**.
2. The list fills in and pings each server. Pick one (or just connect — the
   fastest is used).
3. Click **Connect**. The local proxy listens on:
   - SOCKS5 `127.0.0.1:10808`
   - HTTP `127.0.0.1:10809`
4. To route system apps automatically, tick **Set Windows system proxy** before
   connecting. It's reverted on disconnect / exit / unexpected core exit.

If you don't use the system-proxy toggle, point your app at the SOCKS5/HTTP
address above manually.

## Build a standalone .exe (optional)

```powershell
pip install pyinstaller
pyinstaller --noconsole --name XrayClient --add-data "xray_client/bin;xray_client/bin" main.py
```
The bundled `xray.exe` is picked up from `xray_client/bin` next to the app.

## Notes / limitations

- This is **local-proxy mode**, not a full system VPN/TUN. Apps that ignore the
  system proxy won't be routed unless you configure them to use the SOCKS5/HTTP
  port. (A TUN mode would need a wintun + tun2socks bridge and admin rights.)
- The system-proxy toggle uses the per-user WinINET settings and is a no-op on
  non-Windows platforms (the app still runs; configure apps manually there).
```
