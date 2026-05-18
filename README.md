# Hermes Agent Mobile Client (for Android, iOS - WIP)


![Hermes Agent Mobile demo](demo/hermes-agent-mobile-demo.gif)

[Download signed Android APK](https://github.com/areu01or00/Hermes-Agent-Mobile-Client/raw/main/apk/hermes-agent-mobile-client-release.apk)

This folder is an open-source snapshot of the current project state: a thin
Android WebView client that loads a running Hermes dashboard.

It is an honest Android-only port attempt, not an upstream Hermes PR.
iOS is not implemented in this repo.

## What this is

- Android app that discovers a reachable Hermes dashboard endpoint on network
  and opens it in WebView.
- Uses Hermes dashboard as the source of truth (sessions/chat/jobs/skills all
  come from Hermes server).
- Owns Android soft-keyboard adaptation in the APK so a stock Hermes dashboard
  can be used without patching each user's PC/VPS checkout.
- Includes a signed APK for quick sideload testing.

## What this is not

- Not a replacement for Hermes server.
- Not a full native Android rewrite of Hermes UI logic.
- Not production-hardened distribution.

## Repo layout

- `android/` - Android Studio / Gradle project
- `apk/hermes-agent-mobile-client-release.apk` - signed APK artifact
- `apk/hermes-agent-mobile-client-debug.apk` - debug APK artifact
- `demo/hermes-agent-mobile-demo.gif` - README-compatible inline demo
- `demo/hermes-agent-mobile-demo.mp4` - trimmed app demo recording
- `scripts/run-emulator.sh` - helper script for emulator startup
- `packages/hermes-mobile-connector/` - `npx` connector command for starting
  Hermes dashboard and printing a mobile URL
- `scripts/setup-vps-dashboard.sh` - legacy VPS dashboard setup wrapper

## Download APK

Direct APK download:

`https://github.com/areu01or00/Hermes-Agent-Mobile-Client/raw/main/apk/hermes-agent-mobile-client-release.apk`

Android may ask you to allow "Install unknown apps" for the browser or file
manager used to open the APK.

## Recommended Setup

1. A working Hermes setup on PC or VPS.
2. Install Tailscale on Android and on the PC/VPS where Hermes runs.
3. Log both devices into the same Tailscale network.
4. Run the connector where Hermes Agent is installed:

```bash
npx github:areu01or00/Hermes-Agent-Mobile-Client install
```

The connector starts Hermes dashboard with embedded chat:

```bash
hermes dashboard --host 0.0.0.0 --port 9119 --no-open --insecure --tui
```

It then prints URLs for the Android app. Prefer the Tailscale URL when present.

Management commands:

```bash
npx github:areu01or00/Hermes-Agent-Mobile-Client status
npx github:areu01or00/Hermes-Agent-Mobile-Client url
npx github:areu01or00/Hermes-Agent-Mobile-Client restart
npx github:areu01or00/Hermes-Agent-Mobile-Client logs
npx github:areu01or00/Hermes-Agent-Mobile-Client uninstall
```

Without Tailscale, same-Wi-Fi local IP can work. VPS/public-IP use requires the
dashboard port to be reachable from the phone network.

## Server-side check

Open in browser (replace host as needed):

`http://<your-host>:9119`

For chat tab support, dashboard must expose embedded chat flag (served HTML
includes `window.__HERMES_DASHBOARD_EMBEDDED_CHAT__=true` when `--tui` is on).

## Use the included APK

Install:

```bash
adb install -r apk/hermes-agent-mobile-client-release.apk
```

Launch app:

- Install Hermes Mobile Connector shows the exact `npx` command.
- Paste connector URL opens the real Hermes dashboard `/chat`.
- Same Wi-Fi auto-discovery probes local Hermes dashboard candidates.
- Use Saved Endpoint reopens the last manually connected dashboard base URL.
- The top chrome includes a connector-state dot button. It checks the saved
  dashboard `/api/status` and reports URL, HTTP status, Hermes version,
  gateway state, and active session count without restarting anything.
- The text-size button adjusts WebView terminal scale for narrow phone screens.
- The power button opens the app menu, including logout/reset.
- If the embedded TUI reports `[session ended]`, the app shows an overlay with
  `Open fresh TUI`. That reloads the saved Hermes `/chat` route so the user can
  resume or create a session inside the real TUI.
- If dashboard navigation fails, the app shows the WebView network/HTTP error
  instead of leaving a blank screen.
- The app wraps WebView with an Android input bridge so soft-keyboard typing,
  Backspace, Enter, Delete, and arrow-key style operations are routed into the
  Hermes xterm chat page.

For VPS-only setups, prefer Tailscale so `9119` does not need to be public.

## Troubleshooting

If login times out after entering a correct URL, verify the server first. A
timeout means the Android WebView is trying to reach the dashboard but the host
or port is not answering from the phone network.

From a PC:

```bash
curl -fsS http://<your-host>:9119/api/status
curl -fsS http://<your-host>:9119/chat
```

On a VPS:

```bash
sudo ss -ltnp | grep 9119
sudo systemctl status hermes-dashboard.service
sudo journalctl -u hermes-dashboard.service -n 100 --no-pager
```

For AWS Lightsail, also confirm the instance firewall has inbound TCP `9119`
open and the instance is reachable on SSH. If both SSH and `9119` time out while
Lightsail says ports are open, rebooting the instance can restore networking.

## Instructions for agents

If a user gives you this repo and says "make it work for me", treat the Android
app as a client and verify the Hermes dashboard first.

Recommended order:

1. Ask where Hermes Agent should run: the user's PC on the same Wi-Fi, a LAN
   machine, or a VPS/cloud host.
2. Verify Hermes exists on that host:

```bash
command -v hermes
hermes --version
```

3. Start the dashboard with embedded TUI support:

```bash
hermes dashboard --host 0.0.0.0 --port 9119 --no-open --insecure --tui
```

4. From another machine on the same route as the phone, verify:

```bash
curl -fsS http://<host>:9119/api/status
curl -fsS http://<host>:9119/chat
```

5. For VPS/cloud hosts, confirm the service is listening on all interfaces and
   the firewall/security group allows inbound TCP `9119`:

```bash
sudo ss -ltnp | grep 9119
```

6. Use the connector command as the normal persistent setup path:

```bash
npx github:areu01or00/Hermes-Agent-Mobile-Client install
```

7. Prefer Tailscale when the phone and Hermes host are not on the same LAN.
8. Only debug the APK after `/api/status` and `/chat` are reachable from the
   phone network. Most connection failures are wrong URL, firewall, dashboard
   not running, dashboard bound to localhost, or missing `--tui`.
9. Do not make hidden edits to the user's Hermes Agent checkout as a default
   fix. This client is intended to work with stock Hermes dashboard. If a
   Hermes-side change is genuinely required, document it as an upstream
   compatibility issue.
10. Known mobile caveat: Android soft-keyboard input is adapted in the APK.
   Terminal scroll inside WebView may still vary by device/WebView version.
11. If a user sees `[session ended]`, use the app overlay to open a fresh TUI
    before changing server code. The Android client does not own Hermes session
    resurrection; it only reopens the real dashboard chat surface.

## Known gaps

- Terminal scroll inside the embedded Hermes TUI may still vary by device and
  WebView version.
- The Hermes TUI startup banner/sigil may appear in landscape but not portrait.
  This appears to be upstream dashboard/TUI responsive behavior when terminal
  columns are narrow, not a launcher icon or APK packaging issue.
- Hermes dashboard plugin pages are inherited from the upstream dashboard, but
  this Android client has not validated every plugin page yet.
- Under the dashboard `Plugins` section, `Kanban` and `Example` are currently
  unvalidated/unknown in this mobile client. They may appear because the stock
  Hermes dashboard exposes them, but they should not be treated as confirmed
  mobile-supported features yet.

## Build APK

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug
```

Output:

`android/app/build/outputs/apk/debug/app-debug.apk`

The published APK in `apk/hermes-agent-mobile-client-release.apk` is signed
with a private release keystore. Keep that keystore private; future update APKs
must use the same key or Android will reject them as updates.

## Notes for contributors

- This repo currently supports Android only.
- If you want iOS support, port the same architecture (thin client + Hermes
  dashboard backend) on iOS.
- Do not require users to patch their Hermes Agent install for mobile keyboard
  behavior. Mobile input fixes belong in the Android client unless they are
  submitted as proper upstream Hermes dashboard changes.
- Terminal scroll on Android WebView is still an active compatibility area. The
  current client attempts wheel and Shift+Arrow event bridging, but contributors
  should verify behavior on real devices before treating it as complete.
- Portrait mode may not show the full Hermes TUI startup banner/sigil because
  the upstream terminal UI changes output at narrow widths.
- Plugin pages such as `Kanban` and `Example` need explicit mobile validation
  before they are documented as working.
