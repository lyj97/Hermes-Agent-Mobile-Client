# Hermes Agent Mobile Client

Android native client for a running [Hermes Agent](https://github.com/NousResearch/hermes-agent) dashboard. The app wraps the Hermes WebUI in a WebView and adds mobile-focused connection flow, auto-login support, terminal input fixes, quick keys, text sizing, and connection health checks.

## What This Is

- A native Android shell around the real Hermes dashboard.
- A small connector helper that starts Hermes dashboard and prints a URL for the phone.
- Android-only for now. iOS is not implemented.

This repo does not replace Hermes Agent and does not reimplement Hermes sessions, chat, jobs, files, skills, or plugins. Those stay owned by Hermes.

## Configure The Server URL

The app does not ship with a private server URL. Before using a build, make sure your Hermes dashboard is reachable from the Android device.

Recommended setup:

```bash
npx github:your-org-or-user/Hermes-Agent-Mobile-Client install
```

The connector starts Hermes dashboard on port `9119` and prints one or more URLs. Open the Android app and choose `Paste connector URL`, then paste the URL printed by the connector.

Manual setup:

```bash
hermes dashboard --host 0.0.0.0 --port 9119 --no-open --insecure --tui
```

Then enter your own reachable endpoint in the app, for example:

```text
https://your-hermes-server.example.com
http://<host>:9119
```

Tailscale is recommended because it avoids exposing port `9119` publicly. Same-Wi-Fi local IPs can also work. Public server access can work, but the port must be reachable from the phone network and secured appropriately for your environment.

## Connector Commands

Run these where Hermes Agent is installed:

```bash
npx github:your-org-or-user/Hermes-Agent-Mobile-Client install
npx github:your-org-or-user/Hermes-Agent-Mobile-Client status
npx github:your-org-or-user/Hermes-Agent-Mobile-Client url
npx github:your-org-or-user/Hermes-Agent-Mobile-Client restart
npx github:your-org-or-user/Hermes-Agent-Mobile-Client logs
npx github:your-org-or-user/Hermes-Agent-Mobile-Client uninstall
```

## App Controls

- `Paste connector URL` opens the real Hermes `/chat` dashboard.
- `Resume saved connection` reopens the last saved dashboard URL.
- `Scan same Wi-Fi` probes local Hermes dashboards on port `9119`.
- Green dot button checks saved connector `/api/status`.
- `A` button changes terminal text size.
- Power button opens logout/reset.
- If the TUI shows `[session ended]`, the app shows `Open fresh TUI`.

## Build

Install Android Studio or the Android command line tools, then configure Java and the Android SDK paths for your machine:

```bash
export JAVA_HOME=/path/to/android-studio/jbr
export ANDROID_HOME=/path/to/Android/Sdk
cd android
./gradlew assembleDebug
```

The debug APK is generated under `android/app/build/outputs/apk/debug/`. APKs and signing keys are intentionally ignored by git.

## If You Are An Agent

When a user gives you this repo and says "make it work", do this:

1. Confirm Hermes Agent is installed on the target PC or server:

```bash
command -v hermes
hermes --version
```

2. Prefer Tailscale if the phone is not on the same LAN as the Hermes machine.
3. Run the connector:

```bash
npx github:your-org-or-user/Hermes-Agent-Mobile-Client install
```

4. Verify the printed URL before debugging Android:

```bash
curl -fsS http://<host>:9119/api/status
curl -fsS http://<host>:9119/chat
```

5. Tell the user to paste the working URL into the Android app.

Do not silently patch the user's Hermes checkout or server. If `/api/status` and `/chat` are not reachable from the phone network, fix reachability first. The APK cannot fix a server that is down, bound to localhost, blocked by firewall, or missing `--tui`.

## Troubleshooting

- `Connection refused`: Hermes dashboard is not listening at that host/port.
- `Connection timed out`: the phone cannot reach the host/port. Use Tailscale or open the network path.
- Blank or dead chat: open the same `/chat` URL in a desktop browser. If it fails there too, debug Hermes first.
- `[session ended]`: use the app's `Open fresh TUI` button.
- Wireless `adb install` hangs: copy the APK to phone Downloads and install from Android file manager.

## Known Limits

- Android WebView client only; no native ACP UI yet.
- Tailscale is recommended but not bundled.
- Portrait mode may not show the full Hermes TUI banner because terminal width is narrow.
- Some inherited Hermes dashboard plugin pages are not validated on mobile yet, including `Kanban` and `Example`.

## Repo Layout

- `android/` - Android Gradle project
- `packages/hermes-mobile-connector/` - GitHub `npx` connector helper
- `demo/` - demo media
- `scripts/` - helper scripts
