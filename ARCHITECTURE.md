# Hermes Agent Mobile Client Architecture

## Project Overview

Hermes Agent Mobile Client is an Android-only mobile client for an existing [Hermes Agent](https://github.com/NousResearch/hermes-agent) dashboard. It does not implement Hermes Agent itself, and it does not reimplement Hermes sessions, chat, jobs, files, skills, or plugins. Those remain owned by the upstream Hermes dashboard and runtime.

The app is a thin Android WebView shell around the real Hermes `/chat` dashboard. Its native code focuses on the mobile-specific problems around connecting to a desktop/VPS dashboard, making the WebView usable on Android, handling terminal keyboard input, fixing xterm.js touch scrolling, and exposing small native controls such as text size, logout/reset, quick terminal keys, and connector status.

The repository also contains a small connector helper exposed through `npx`. The connector runs on the machine where Hermes Agent is installed. It starts Hermes dashboard on `0.0.0.0:9119`, prefers private Tailscale URLs, and prints a URL that the Android app can open.

## High-Level Architecture

```text
Android phone
  Hermes Agent Mobile app
    MainActivity
      Native connection hub HTML
      HermesWebView
        WebView settings
        JS injection layer
        JS -> Kotlin focus bridge
        Kotlin -> JS terminal input bridge
      DashboardDiscoveryService
      HermesPreferences
    |
    | HTTP to Hermes dashboard
    v
Hermes host machine
  hermes-mobile connector helper
    Linux: systemd service
    macOS: launchd agent
    |
    v
  hermes dashboard --host 0.0.0.0 --port 9119 --no-open --insecure --tui
    /api/status
    /chat
```

The app has three main layers:

1. Native Android shell: `MainActivity`, `HermesWebView`, preferences, dialogs, quick keys, status bar handling, and connectivity flow.
2. WebView injection layer: `WebViewInjectors` patches the loaded Hermes dashboard at runtime with mobile controls, terminal input forwarding, terminal touch scrolling, dead-session detection, and terminal relayout triggers.
3. Connector/runtime layer: `packages/hermes-mobile-connector/` starts and manages the real Hermes dashboard on the host machine.

The app normally starts from a native-generated connection hub. From there the user can resume a saved dashboard URL, paste a connector URL, copy the connector install command, or scan the same Wi-Fi network. Once a dashboard base URL is selected, the app loads:

```text
http://<host>:9119/chat
```

and verifies status through:

```text
http://<host>:9119/api/status
```

## Android App Architecture

The Android project lives in `android/`. It is a Kotlin Android application using a single Activity and no Fragments.

### Package Structure

Kotlin package:

```text
dev.hermes.mobile
```

Key source files:

```text
android/app/src/main/java/dev/hermes/mobile/
  DashboardDiscoveryService.kt
  HermesConfig.kt
  HermesPreferences.kt
  HermesWebView.kt
  MainActivity.kt
  WebViewInjectors.kt
```

Resource and build files:

```text
android/app/src/main/AndroidManifest.xml
android/app/src/main/res/values/styles.xml
android/app/build.gradle
android/build.gradle
android/settings.gradle
android/gradle.properties
```

### Activity, View, and Fragment Hierarchy

There are no Fragments. `MainActivity` extends `androidx.activity.ComponentActivity` and builds its view hierarchy programmatically.

Runtime view hierarchy:

```text
MainActivity
  LinearLayout root, vertical
    View statusBarView
    HermesWebView webView
    HorizontalScrollView quickKeysBar
      LinearLayout
        TextView quick-key buttons
```

`MainActivity` owns:

- WebView creation and configuration.
- Connection hub and status-page HTML rendering.
- Native dialogs for manual endpoint entry, connector command copy, connector status, text size, and logout/reset.
- Back navigation through `OnBackPressedCallback`.
- IME inset handling and quick-key bar visibility.
- WebView state save/restore on configuration changes.
- Status-bar color sampling from the top rows of the WebView via `PixelCopy`.

The `statusBarView` is a native spacer because the app draws edge-to-edge with:

```kotlin
WindowCompat.setDecorFitsSystemWindows(window, false)
window.statusBarColor = Color.TRANSPARENT
```

Insets are handled manually. The IME does not resize the Activity through the system because the manifest uses `android:windowSoftInputMode="adjustNothing"`. Instead, `MainActivity.updateImeLayout()` applies bottom padding and shows the quick-key bar when the keyboard is visible.

### WebView Setup and Configuration

`MainActivity.onCreate()` creates a `HermesWebView` and configures standard dashboard settings:

```kotlin
settings.javaScriptEnabled = true
settings.domStorageEnabled = true
settings.mediaPlaybackRequiresUserGesture = false
settings.cacheMode = WebSettings.LOAD_DEFAULT
settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
settings.useWideViewPort = true
settings.loadWithOverviewMode = false
settings.setSupportZoom(false)
settings.builtInZoomControls = false
settings.displayZoomControls = false
settings.textZoom = hermesPreferences.getSavedTextZoom()
settings.userAgentString = "${settings.userAgentString} ${HermesConfig.UA_SUFFIX}"
```

Important constants from `HermesConfig`:

```text
HERMES_DEFAULT_PORT = 9119
ENDPOINT_CHAT = /chat
ENDPOINT_API_STATUS = /api/status
UA_SUFFIX = HermesAgentMobile/0.1
TEXT_ZOOM_MIN = 60
TEXT_ZOOM_MAX = 160
TEXT_ZOOM_DEFAULT = 90
```

Cookies are enabled globally with `CookieManager.getInstance().setAcceptCookie(true)`. WebView remote debugging is disabled through `WebView.setWebContentsDebuggingEnabled(false)`.

The WebView allows mixed content and the manifest allows cleartext traffic because the expected dashboard URL is commonly plain HTTP over Tailscale, LAN, or a reachable VPS address.

### URL Routing

The app uses an internal URL scheme to route WebView link clicks back to native Android actions:

```text
hermes://discover
hermes://manual
hermes://saved
hermes://script
hermes://menu
hermes://textsize
hermes://connector
hermes://reloadtui
```

`MainActivity.handleInternalUrl()` intercepts these URLs in both `shouldOverrideUrlLoading()` overloads. External `http://` and `https://` URLs are left to the WebView.

Internal actions:

- `discover`: run local network discovery.
- `manual`: show endpoint paste dialog.
- `saved`: load saved dashboard base, or prompt manually if none exists.
- `script`: show and copy the `npx` connector command.
- `menu`: show logout/reset menu.
- `textsize`: show text zoom slider.
- `connector`: query saved dashboard `/api/status`.
- `reloadtui`: reopen the saved dashboard `/chat` URL.

### JavaScript Bridges

There are two named bridge concepts in the code.

#### JS -> Kotlin Focus Bridge

Kotlin registers a JavaScript interface:

```kotlin
addJavascriptInterface(..., HermesConfig.JS_BRIDGE_FOCUS)
```

with:

```text
HermesConfig.JS_BRIDGE_FOCUS = HermesFocusBridge
method = setXtermHelperTextareaFocused(focused: Boolean)
```

The injected JavaScript in `WebViewInjectors.injectMobileInputBridge()` tracks focus on `.xterm-helper-textarea` using `focusin` and `focusout`. It calls:

```javascript
window.HermesFocusBridge.setXtermHelperTextareaFocused(focused)
```

`MainActivity` then updates `HermesWebView.xtermHelperTextareaFocused` and calls `InputMethodManager.restartInput(webView)` only when the focus state actually changes. The code explicitly suppresses repeated no-op notifications to avoid crash-inducing rapid `restartInput()` calls during DOM mutations, login transitions, or React re-renders.

#### Kotlin -> JS Terminal Input Bridge

`WebViewInjectors.injectMobileInputBridge()` creates:

```javascript
window.HermesMobileNativeInput = {
  text: sendText,
  key: sendKey
}
```

Kotlin sends text and keys by evaluating JavaScript:

```kotlin
window.HermesMobileNativeInput && window.HermesMobileNativeInput.text(...)
window.HermesMobileNativeInput && window.HermesMobileNativeInput.key(...)
```

Supported native key names are:

```text
backspace
delete
enter
up
down
left
right
```

The JavaScript bridge focuses `.xterm-helper-textarea`, `.xterm textarea`, or `.xterm`, then dispatches `InputEvent` and `KeyboardEvent` objects expected by xterm.js.

### Custom Classes and Responsibilities

#### MainActivity

`MainActivity` is the application coordinator. Responsibilities:

- Builds the entire native view hierarchy.
- Configures the WebView.
- Handles connection flow: saved URL, manual URL, connector script copy, same-Wi-Fi discovery.
- Loads the Hermes dashboard at `<base>/chat`.
- Warms up `<base>/api/status` before loading.
- Injects mobile JavaScript after page load.
- Displays native dialogs for connector status, text zoom, endpoint entry, and logout.
- Manages text zoom persistence and terminal relayout/reload after zoom changes.
- Provides quick terminal keys: `Tab`, `Ctrl+C`, `Esc`, arrows, `/`, `-`, `~`, `|`, `:`.
- Samples WebView top color and animates the native status-bar spacer color.
- Saves/restores WebView state across configuration changes.

#### HermesWebView

`HermesWebView` subclasses `android.webkit.WebView` to handle terminal-specific input and scroll behavior.

When xterm's hidden textarea is not focused, it falls back to the normal WebView input path:

```kotlin
return super.onCreateInputConnection(outAttrs)
```

When `.xterm-helper-textarea` is focused, it returns a custom `BaseInputConnection` that forwards text and key events to `MainActivity` through `MobileInputSink`. This avoids breaking normal login/chat text fields while still making xterm terminal input work with Android IMEs.

It also overrides:

```kotlin
override fun scrollTo(x: Int, y: Int) { }
override fun scrollBy(x: Int, y: Int) { }
```

The comment explains why: the Hermes WebUI scrolls inside JavaScript-managed overflow regions, while the native WebView scroll position remains `0`. Making native WebView scrolling a no-op prevents the native gesture recognizer from competing with the injected terminal touch-to-wheel bridge.

#### WebViewInjectors

`WebViewInjectors` contains all dashboard JavaScript/CSS injection.

It provides:

- `injectMobileChrome()`: adds mobile controls to the loaded dashboard, including connector status, text size, power/logout, and dead-session banner.
- `injectMobileInputBridge()`: installs `window.HermesMobileNativeInput` and the xterm focus tracker.
- `injectTerminalTouchWheelBridge()`: converts xterm touch gestures into `WheelEvent`s.
- `triggerTerminalRelayout()`: forces resize/orientation/visual viewport events so xterm can refit after load or text zoom changes.

The injected mobile chrome looks for visible Hermes Agent brand text and appends controls there when possible. If no suitable host is found, it falls back to fixed-position controls near the top-left of the page.

The dead-session detector watches page text for strings including:

```text
[session ended]
gateway exited
chat unavailable
```

When detected, it shows an injected `Open fresh TUI` link that routes to `hermes://reloadtui`.

#### DashboardDiscoveryService

`DashboardDiscoveryService` discovers and validates dashboard base URLs.

Discovery paths:

1. mDNS via Android `NsdManager`, scanning `_http._tcp.` services whose names contain `hermes`.
2. LAN probing over the local `/24` subnet derived from the device IPv4 address.

Validation calls:

```text
GET <base>/api/status
```

and requires:

- HTTP status `200`.
- Response body contains `"version"`.
- Response body contains `"gateway_running"`.

LAN probe uses a fixed pool of 32 threads, scans hosts `1..254`, and stops after finding a matching dashboard.

`normalizeDashboardBase()` accepts raw input, adds `http://` when missing, preserves `https://` when present, keeps explicit ports, strips query/fragment fallback text, and removes a trailing `/chat` during fallback normalization.

#### HermesPreferences

`HermesPreferences` wraps `SharedPreferences` under:

```text
hermes_mobile_client
```

Stored keys:

```text
last_dashboard_base
text_zoom
```

It normalizes saved dashboard bases when read and clamps saved text zoom between `60` and `160`.

#### HermesConfig

`HermesConfig` centralizes:

- Dashboard port and endpoints.
- Timeout values.
- Text zoom limits/defaults.
- WebView user-agent suffix.
- JS bridge names.
- Internal `hermes://` URL scheme.
- Quick-key dimensions and colors.

### Manifest Permissions and Features

`android/app/src/main/AndroidManifest.xml` declares:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

Application attributes:

```xml
android:allowBackup="true"
android:usesCleartextTraffic="true"
android:theme="@style/HermesTheme"
android:label="Hermes Agent"
```

Activity attributes:

```xml
android:name=".MainActivity"
android:configChanges="orientation|screenSize|keyboardHidden"
android:exported="true"
android:windowSoftInputMode="adjustNothing"
```

The Activity is the launcher entry point through the standard `MAIN` / `LAUNCHER` intent filter.

The theme is `android:style/Theme.Material.NoActionBar`, disables title/action bar, uses a transparent status bar, dark navigation bar, and dark status-bar icons are disabled through `android:windowLightStatusBar=false`.

## Connector Helper

The connector helper lives under:

```text
packages/hermes-mobile-connector/
  bin/hermes-mobile.js
  scripts/hermes-mobile.sh
```

There is no package-local `package.json` in this repository. The root `package.json` defines the package metadata and exposes the bin:

```json
{
  "name": "hermes-agent-mobile-client",
  "version": "0.1.0",
  "private": true,
  "bin": {
    "hermes-mobile": "packages/hermes-mobile-connector/bin/hermes-mobile.js"
  },
  "files": [
    "packages/hermes-mobile-connector"
  ]
}
```

`bin/hermes-mobile.js` is intentionally small. It uses Node's `spawnSync()` to run:

```text
packages/hermes-mobile-connector/scripts/hermes-mobile.sh
```

with the original CLI arguments and inherited stdio.

### Purpose and Design

The connector is meant to be run on the same machine where Hermes Agent is installed. It starts Hermes dashboard as a persistent service and prints mobile-reachable URLs. It is not a proxy and does not provide a separate mobile backend.

The dashboard command it installs is:

```bash
hermes dashboard --host 0.0.0.0 --port 9119 --no-open --insecure --tui
```

It also sets:

```text
HERMES_DASHBOARD_TUI=1
```

Default connector settings can be overridden with environment variables:

```text
HERMES_DASHBOARD_PORT
HERMES_DASHBOARD_HOST
HERMES_BIN
HERMES_RUN_USER
HERMES_WORKDIR
```

### Supported Commands

The shell script supports:

```text
install
start
stop
restart
repair
status
url
urls
logs
uninstall
```

The usage string currently prints:

```text
hermes-mobile [install|start|stop|restart|status|url|logs|uninstall]
```

`repair` is an alias for stop/start/status in the `case` statement, even though it is not listed in the usage string.

### How It Starts and Manages Hermes

On Linux, `install` writes:

```text
/etc/systemd/system/hermes-mobile-dashboard.service
```

with:

```ini
Type=simple
Restart=always
RestartSec=3
KillSignal=SIGINT
TimeoutStopSec=20
```

It then runs:

```bash
sudo systemctl daemon-reload
sudo systemctl enable hermes-mobile-dashboard.service
sudo systemctl restart hermes-mobile-dashboard.service
```

On macOS, `install` writes:

```text
~/Library/LaunchAgents/dev.hermes.mobile.dashboard.plist
```

with `RunAtLoad` and `KeepAlive`, then loads and restarts it through `launchctl`.

After installation, it polls local health:

```text
http://127.0.0.1:9119/api/status
```

If healthy, it prints candidate mobile URLs in this order:

1. Tailscale DNS name from `tailscale status --json`.
2. Tailscale IPv4 from `tailscale ip -4`.
3. LAN IP.
4. Public IP from `https://api.ipify.org`.

The first non-empty URL is printed as `Recommended`; later ones are printed as `Fallback`. If Tailscale is present, the script explicitly recommends the Tailscale URL for private cross-network access.

Other commands:

- `start`: starts the systemd service or launchd agent.
- `stop`: stops the systemd service or launchd agent.
- `restart` / `repair`: stop, start, then print status.
- `status`: prints service active/inactive, health ok/failed, and URLs.
- `url` / `urls`: prints candidate URLs.
- `logs`: prints launchd logs on macOS or `journalctl` logs on Linux.
- `uninstall`: stops service, removes service/agent file, and does not touch Hermes Agent data.

The legacy `scripts/setup-vps-dashboard.sh` is kept for users who copied the old VPS script path. Its own comments direct users to prefer the `npx ... install` connector flow.

## Key Technical Decisions

### WebView Instead of Native UI

The codebase uses WebView because the app is intended to open the real Hermes dashboard, not replace it. The README states that Hermes sessions, chat, jobs, files, skills, and plugins stay owned by Hermes. The native app only adapts the existing dashboard for Android.

This keeps the Android app small and avoids duplicating upstream Hermes behavior. The tradeoff is that mobile ergonomics depend on runtime JavaScript injection and the upstream dashboard DOM, especially around xterm.js terminal behavior.

### Network and Connectivity Approach

The expected deployment is:

```text
Android app -> HTTP -> Hermes dashboard on host machine port 9119
```

Tailscale is the recommended network path because it avoids exposing port `9119` publicly and avoids router/cloud firewall setup. Same-Wi-Fi LAN access can also work, and a public VPS can work if TCP `9119` is reachable from the phone network.

The Android app supports:

- Manual connector URL paste.
- Resume saved connector URL.
- Same-Wi-Fi discovery.
- Connector status check through `/api/status`.

The connector supports:

- Binding the dashboard to `0.0.0.0`.
- Printing Tailscale DNS/IP first.
- Falling back to LAN and public IP candidates.

The app allows cleartext traffic because the dashboard is usually `http://` on a private Tailscale or LAN address. There is no TLS setup or bundled VPN inside the app.

### Immersive and Fullscreen Handling

The app draws edge-to-edge using `WindowCompat.setDecorFitsSystemWindows(window, false)` and makes the system status bar transparent. It does not use an XML layout or native toolbar. Instead, it creates a top native `statusBarView` whose height follows `WindowInsetsCompat.Type.statusBars()`.

To make the top strip feel integrated with the WebView content, `MainActivity` samples the top eight rows of the WebView using `PixelCopy`, computes an average color, and animates the native status-bar spacer to match.

Keyboard handling is similarly manual. The Activity declares `adjustNothing`, listens to IME insets and IME animation progress, shows the quick-key bar while the keyboard is visible, and pads the root view by the IME height.

### Terminal Scroll Fix

`SCROLL_FIX_NOTES.md` documents the core issue: xterm.js listens to `WheelEvent`, but Android WebView touch scrolling produces `TouchEvent`. As a result, normal page scroll can work while the internal xterm terminal region does not respond to touch scrolling.

The selected strategy is Android-only JavaScript injection through `evaluateJavascript`, without modifying Hermes WebUI source. This keeps the Android client decoupled from the upstream Hermes WebUI.

The current implementation is in `WebViewInjectors.injectTerminalTouchWheelBridge()` and has evolved beyond the original notes in one important detail:

- The notes describe dispatching a bubbling `WheelEvent` to `.xterm-scrollable-element`, `.xterm-viewport`, or `.xterm`.
- The code now installs listeners only on `.xterm` roots and dispatches a non-bubbling pixel-mode `WheelEvent` to `.xterm-scrollable-element` when present.

The reason is documented in the source comments. xterm has separate wheel handling on `.xterm-scrollable-element` and `.xterm`; dispatching to `.xterm` or allowing bubbling can trigger a fixed-step line scroll handler that makes fast swipes scroll less than slow swipes. Dispatching to `.xterm-scrollable-element` with `bubbles:false` preserves pixel-proportional scroll behavior.

The Kotlin side reinforces this by making `HermesWebView.scrollTo()` and `HermesWebView.scrollBy()` no-ops, so native WebView scrolling does not fight the JavaScript bridge.

## Data Flow

### Connection Flow

```text
User opens app
  MainActivity.onCreate()
    load saved WebView state, or
    load saved dashboard base, or
    render connection hub HTML

User chooses connection action
  hermes://manual   -> native endpoint dialog
  hermes://saved    -> load saved base
  hermes://discover -> DashboardDiscoveryService
  hermes://script   -> native connector command dialog

Dashboard base selected
  DashboardDiscoveryService.normalizeDashboardBase()
  HermesPreferences.saveDashboardBase()
  warmup GET <base>/api/status
  WebView.loadUrl(<base>/chat)
```

### User Tap to Hermes Response

For normal dashboard UI:

```text
User taps inside WebView
  Android WebView delivers touch/click/input to loaded Hermes dashboard
  Hermes dashboard JavaScript handles the action
  Dashboard calls Hermes backend over HTTP/WebSocket/fetch as implemented upstream
  Hermes backend updates the dashboard
  WebView renders the response
```

For terminal text input:

```text
User focuses xterm terminal
  Injected JS detects .xterm-helper-textarea focus
  window.HermesFocusBridge.setXtermHelperTextareaFocused(true)
  MainActivity updates HermesWebView.xtermHelperTextareaFocused
  Android IME asks HermesWebView for InputConnection
  HermesWebView returns custom BaseInputConnection

User types
  BaseInputConnection.commitText() or sendKeyEvent()
  HermesWebView.MobileInputSink
  MainActivity.sendHermesMobileText() / sendHermesMobileKey()
  WebView.evaluateJavascript()
  window.HermesMobileNativeInput.text() / key()
  Injected JS dispatches InputEvent / KeyboardEvent to xterm
  Hermes dashboard terminal sends input to Hermes runtime
  Hermes response appears in xterm
```

For terminal touch scroll:

```text
User swipes inside .xterm
  Injected touchmove listener records delta
  preventDefault() and stopPropagation()
  Dispatch WheelEvent(deltaMode=DOM_DELTA_PIXEL, bubbles=false)
  Target: .xterm-scrollable-element if present
  xterm scrolls internally
```

For quick keys:

```text
User taps native quick-key TextView
  MainActivity action sends text/key or direct KeyboardEvent JS
  xterm receives Tab, Ctrl+C, Esc, arrows, or punctuation
```

## Build and Release

### Gradle Setup

The Android project is a standard Gradle project:

```text
android/settings.gradle
  rootProject.name = HermesAgentMobileAndroid
  include :app

android/build.gradle
  com.android.application 8.7.3
  org.jetbrains.kotlin.android 2.0.21

android/app/build.gradle
  namespace dev.hermes.mobile
  applicationId dev.hermes.mobile
  compileSdk 36
  minSdk 26
  targetSdk 36
  versionCode 1
  versionName 0.1.0
  JavaVersion.VERSION_21
  kotlin.jvmToolchain(21)
```

Dependencies are minimal:

```gradle
implementation "androidx.activity:activity-ktx:1.9.3"
implementation "androidx.core:core-ktx:1.13.1"
```

`android/gradle.properties` enables AndroidX, non-transitive `R`, suppresses unsupported compile SDK warnings for SDK 36, and sets Gradle JVM args.

Build command from the README:

```bash
cd android
./gradlew assembleRelease
```

### Signing and APK Distribution

The repository contains checked-in APK artifacts:

```text
apk/hermes-agent-mobile-client-debug.apk
apk/hermes-agent-mobile-client-release.apk
```

The README links directly to the checked-in release APK and says it is signed with a private release key. There is no signing configuration in `android/app/build.gradle`, and no keystore is present in the repository. Future update APKs must be signed with the same private key or Android will reject them as updates to the existing installed app.

### Emulator Helper

`scripts/run-emulator.sh` creates or reuses an API 36 Google APIs x86_64 AVD, installs a debug APK, and launches:

```text
dev.hermes.mobile/.MainActivity
```

It expects the APK at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

unless overridden with `HERMES_ANDROID_APK`.

## Known Limitations

Known limitations documented in the README:

- Android WebView client only; there is no native ACP UI yet.
- iOS is not implemented.
- Tailscale is recommended but not bundled.
- Portrait mode may not show the full Hermes TUI banner/sigil because terminal width is narrow.
- Some inherited Hermes dashboard plugin pages are not validated on mobile, including `Kanban` and `Example`.

Limitations visible from the code:

- The app depends on the upstream Hermes dashboard DOM for xterm selectors such as `.xterm`, `.xterm-helper-textarea`, and `.xterm-scrollable-element`.
- Dashboard discovery only checks mDNS service names containing `hermes` and one local `/24` IPv4 subnet.
- LAN discovery requires the Android device to have a local IPv4 address on interfaces whose names start with `wlan`, `eth`, or `en`.
- `/api/status` validation assumes the response contains `"version"` and `"gateway_running"`.
- Connector status parsing is best-effort and accepts several possible JSON field names, but does not enforce a strict schema.
- Cleartext HTTP is intentionally allowed; TLS/certificate management is outside this app.
- `RECORD_AUDIO` is declared, but the current Kotlin source does not implement explicit runtime microphone permission handling.
- The connector supports Linux and macOS service installation. There is no Windows service installer in the connector script.
- The root package is marked `"private": true`; the documented install path uses GitHub `npx`, not a published npm package workflow.
- The app does not bundle or start Hermes Agent. Hermes must already be installed and reachable on the host.
