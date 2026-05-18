# Hermes Agent Mobile Client

Android client for a running [Hermes Agent](https://github.com/NousResearch/hermes-agent) dashboard.

![Hermes Agent Mobile demo](demo/hermes-agent-mobile-demo.gif)

[Download signed Android APK](https://github.com/areu01or00/Hermes-Agent-Mobile-Client/raw/main/apk/hermes-agent-mobile-client-release.apk)

## What This Is

- A thin Android WebView client for the real Hermes dashboard.
- A small connector helper that starts Hermes dashboard and prints a URL for the phone.
- Android-only for now. iOS is not implemented.

This repo does not replace Hermes Agent and does not reimplement Hermes sessions, chat, jobs, files, skills, or plugins. Those stay owned by Hermes.

## Quick Start

1. Install the APK on Android.
2. Install Tailscale on Android and on the machine where Hermes Agent runs.
3. Log both devices into the same Tailscale network.
4. On the Hermes machine, run:

```bash
npx github:areu01or00/Hermes-Agent-Mobile-Client install
```

5. Copy the URL printed by the connector.
6. Open the Android app and choose `Paste connector URL`.

The connector starts Hermes like this:

```bash
hermes dashboard --host 0.0.0.0 --port 9119 --no-open --insecure --tui
```

## Connector Commands

Run these where Hermes Agent is installed:

```bash
npx github:areu01or00/Hermes-Agent-Mobile-Client install
npx github:areu01or00/Hermes-Agent-Mobile-Client status
npx github:areu01or00/Hermes-Agent-Mobile-Client url
npx github:areu01or00/Hermes-Agent-Mobile-Client restart
npx github:areu01or00/Hermes-Agent-Mobile-Client logs
npx github:areu01or00/Hermes-Agent-Mobile-Client uninstall
```

Tailscale is recommended because it avoids exposing port `9119` publicly. Same-Wi-Fi local IP can also work. Public VPS access can work, but then `9119` must be reachable from the phone network.

## App Controls

- `Paste connector URL` opens the real Hermes `/chat` dashboard.
- `Resume saved connection` reopens the last saved dashboard URL.
- `Scan same Wi-Fi` probes local Hermes dashboards on port `9119`.
- Green dot button checks saved connector `/api/status`.
- `A` button changes terminal text size.
- Power button opens logout/reset.
- If the TUI shows `[session ended]`, the app shows `Open fresh TUI`.

## If You Are An Agent

When a user gives you this repo and says "make it work", do this:

1. Confirm Hermes Agent is installed on the target PC/VPS:

```bash
command -v hermes
hermes --version
```

2. Prefer Tailscale if the phone is not on the same LAN as the Hermes machine.
3. Run the connector:

```bash
npx github:areu01or00/Hermes-Agent-Mobile-Client install
```

4. Verify the printed URL before debugging Android:

```bash
curl -fsS http://<host>:9119/api/status
curl -fsS http://<host>:9119/chat
```

5. Tell the user to paste the working URL into the Android app.

Do not silently patch the user's Hermes checkout or VPS. If `/api/status` and `/chat` are not reachable from the phone network, fix reachability first. The APK cannot fix a server that is down, bound to localhost, blocked by firewall, or missing `--tui`.

## Troubleshooting

- `Connection refused`: Hermes dashboard is not listening at that host/port.
- `Connection timed out`: the phone cannot reach the host/port. Use Tailscale or open the network path.
- Blank or dead chat: open the same `/chat` URL in a desktop browser. If it fails there too, debug Hermes first.
- `[session ended]`: use the app's `Open fresh TUI` button.
- Wireless `adb install` hangs: copy the APK to phone Downloads and install from Android file manager.

## Known Limits

- Android WebView client only; no native ACP UI yet.
- Tailscale is recommended but not bundled.
- Portrait mode may not show the full Hermes TUI banner/sigil because terminal width is narrow.
- Some inherited Hermes dashboard plugin pages are not validated on mobile yet, including `Kanban` and `Example`.

## Repo Layout

- `android/` - Android Gradle project
- `apk/hermes-agent-mobile-client-release.apk` - signed APK
- `packages/hermes-mobile-connector/` - GitHub `npx` connector helper
- `demo/` - demo media
- `scripts/` - helper scripts

## Build

```bash
cd android
./gradlew assembleRelease
```

The checked-in release APK is signed with a private release key. Future update APKs must use the same key or Android will reject them as app updates.
