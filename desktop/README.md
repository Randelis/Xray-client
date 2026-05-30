# Xray Client — Desktop (Windows)

A PC version of the Android Xray client. It runs **xray-core** as a local
SOCKS5 / HTTP proxy and lets you import VLESS / VMess / Trojan links or a
subscription URL, probe server latency, and connect with one click. It offers
three connection modes — including a full **TUN mode** that routes *every* app
on the system through the tunnel (games, Discord, anything), not just browsers.

> Built with Python + PySide6. It mirrors the Android app's config/parsing logic.

## Features

- Import a single `vless://` / `vmess://` / `trojan://` link, many at once
  (one per line), or a **subscription URL** (base64 bodies are auto-decoded).
- Server list with live TCP-RTT ping (weighted moving average), sorted fastest first.
- One-click connect/disconnect; the fastest (or selected) server is used.
- Full VLESS/VMess/Trojan outbound generation incl. **TLS / Reality** and
  **ws / grpc / h2 / tcp-http** transports.
- **Three modes** (dropdown, bottom-right):
  - **System proxy** — sets the per-user Windows proxy; browsers & WinINET apps follow it.
  - **TUN (all apps)** — system-wide capture via wintun + tun2socks; *everything* is routed.
  - **Manual** — just exposes SOCKS5/HTTP; you point apps at it yourself.
- Servers persist between runs (`%APPDATA%\XrayClient\nodes.json`).

## How TUN mode works

```
all OS traffic ─▶ wintun TUN device ─▶ tun2socks ─▶ SOCKS5 (xray) ─▶ your server
```

The tricky parts, handled automatically:

- **Server-IP bypass route** — before routing is hijacked, the app resolves your
  server's address and pins xray to that exact IP, then adds a `/32` route for it
  via your real gateway. This stops the tunnel's own underlying connection from
  looping back into itself. (SNI/TLS still uses the original hostname, so it validates.)
- **Catch-all split routes** — two `/1` routes (`0.0.0.0/1` + `128.0.0.0/1`) send
  everything else through the TUN. They beat the existing default route by
  specificity, so your original default route is left intact and restore is clean.
- **DNS through the tunnel** — the TUN adapter's DNS is set to `1.1.1.1`, so name
  lookups also go through xray (no DNS leak to your ISP).
- All routes/DNS changes are tracked and reverted on disconnect, exit, or if the
  core crashes.

TUN mode is **Windows-only** and needs **administrator rights** (to edit routes).
If you start it without elevation, the app offers to relaunch via UAC.

## Portable download — no install, no setup

There's nothing to install. A GitHub Actions workflow builds **one single
`XrayClient.exe`** with xray-core, tun2socks, wintun and the Python runtime all
bundled inside. Just download it and double-click.

1. Open the repo's **Actions** tab → **Build Windows EXE** → the latest run.
2. Download the **`XrayClient-Portable-windows`** artifact (a zip containing
   `XrayClient.exe`). Unzip and run it. Done.

For a permanent download link, push a tag like `v1.0.0` (or create a Release) —
the same workflow attaches `XrayClient.exe` to the **Release**.

### What "portable" means here

- **One file.** No Python, no separate xray/tun2socks/wintun downloads, no installer.
- **Self-contained settings.** Your imported servers are saved in an
  `XrayClient-Data` folder created **next to the .exe** (not in `%APPDATA%`). Keep
  the `.exe` and that folder together — drop them on a USB stick or any folder and
  it just works. Delete both to leave **no trace** on the machine.
  - If the `.exe` sits somewhere read-only (e.g. `Program Files`), it
    automatically falls back to `%APPDATA%\XrayClient`.
  - Override the location any time with the `XRAYCLIENT_DATA` environment variable.

> TUN mode still needs admin — the app shows a UAC prompt when you connect.
> Windows SmartScreen may warn about an unsigned exe the first time: choose
> **More info → Run anyway** (or sign it with your own code-signing certificate).

## Setup (run from source instead)

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

4. **(For TUN mode) provide tun2socks + wintun.** Both go in the same `bin/` folder:
   - `tun2socks.exe` — from <https://github.com/xjasonlyu/tun2socks/releases>
   - `wintun.dll` — from <https://www.wintun.net> (use the `amd64` build for 64-bit Windows)
   ```
   desktop/xray_client/bin/tun2socks.exe
   desktop/xray_client/bin/wintun.dll
   ```
   You don't need these for System-proxy or Manual mode.

5. **Run:**
   ```powershell
   python main.py
   ```
   For TUN mode, run from an **Administrator** terminal (or accept the UAC prompt
   the app shows when you connect).

## Usage

1. Click **Import**, paste your VLESS link(s) or subscription URL, click **Import**.
2. The list fills in and pings each server. Pick one (or just connect — the
   fastest is used).
3. Choose a **Mode** (dropdown, bottom-right):
   - **TUN (all apps)** — recommended; routes everything system-wide. Needs admin.
   - **System proxy** — browsers and WinINET apps only.
   - **Manual** — point apps at SOCKS5 `127.0.0.1:10808` / HTTP `127.0.0.1:10809`.
4. Click **Connect**. All routing/proxy/DNS changes are reverted automatically on
   disconnect, exit, or unexpected core exit.

## Build a standalone .exe (optional)

```powershell
pip install pyinstaller
pyinstaller --noconsole --name XrayClient --add-data "xray_client/bin;xray_client/bin" main.py
```
The bundled `xray.exe`, `tun2socks.exe`, and `wintun.dll` are picked up from
`xray_client/bin` next to the app. To launch elevated by default, add a manifest
with `requestedExecutionLevel=requireAdministrator`.

## Notes / limitations

- **TUN mode is Windows-only** and requires admin + the `tun2socks.exe` and
  `wintun.dll` binaries. On macOS/Linux the mode is disabled in the UI (the app
  still runs in System-proxy/Manual mode).
- TUN routing covers **IPv4** fully. If you have native IPv6 and want zero
  IPv6 leakage, disable IPv6 on your adapter or rely on the IPv4-only routing
  (most blocked services resolve over IPv4 fine).
- The TUN adapter name / addresses / DNS are constants in
  `xray_client/netutil.py` (`XrayTun`, `10.10.10.2`, DNS `1.1.1.1`) — change them
  there if they clash with your network.
- The system-proxy mode uses per-user WinINET settings and is a no-op on
  non-Windows platforms.
