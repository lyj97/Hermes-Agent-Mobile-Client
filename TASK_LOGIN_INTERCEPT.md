# Task: Intercept WebView login page and show native credentials dialog

## Context

The app already has:
- `hermesPreferences.saveCredentials(baseUrl, username, password)`
- `hermesPreferences.loadCredentials(baseUrl): Pair<String, String>?`
- `hermesPreferences.clearCredentials(baseUrl)`
- `attemptAutoLogin(baseUrl): Boolean` — does a background HTTP POST to `/hermes-login`
- `showCredentialsDialog(baseUrl, onComplete)` — native AlertDialog with username+password fields

## New Behavior to Add

In `MainActivity.kt`, inside the `webViewClient = object : WebViewClient()` block, override `onPageFinished`:

**Current `onPageFinished`** already calls `WebViewInjectors.injectMobileChrome(...)` etc.

**Add this logic** at the start of `onPageFinished`, BEFORE the existing injector calls:

```
if the loaded URL ends with "/hermes-login" or contains "/hermes-login":
    → the WebView has landed on the proxy login page
    → call showLoginInterceptDialog(baseUrl)
    → return early (skip injectors for this page — they're not needed on the login page)
```

### `showLoginInterceptDialog(baseUrl: String)`

This is a NEW private function in MainActivity. It should:

1. Load any saved credentials via `hermesPreferences.loadCredentials(baseUrl)`
2. Show a native AlertDialog (same style as existing `showCredentialsDialog`):
   - Title: `"Log in to Hermes"`
   - Message: `"Enter credentials to connect."`
   - Two EditText fields: username (pre-filled), password (pre-filled, inputType=textPassword)
   - Button [Login]:
     - If both fields non-empty:
       - Save credentials: `hermesPreferences.saveCredentials(baseUrl, username, password)`
       - Run `attemptAutoLogin(baseUrl)` on `startupExecutor` (background thread)
       - On completion (back on mainHandler): `webView.loadUrl("$baseUrl${HermesConfig.DASHBOARD_PATH}")` (i.e. `/chat`)
     - If fields empty: just reload `/chat` (let WebView handle it — user may want to fill form manually)
   - Button [Fill form manually]: dismiss dialog, do nothing — user fills the web form in WebView themselves

3. `baseUrl` to use: derive it from the loaded URL by stripping `/hermes-login` suffix.
   Example: `"https://nas.2045.site:19120/hermes-login"` → `"https://nas.2045.site:19120"`
   Use: `url.substringBefore("/hermes-login")`

### Thread safety

- `showLoginInterceptDialog` must be called on the main thread (it's already in `onPageFinished` which runs on main thread ✓)
- `attemptAutoLogin` must run on `startupExecutor` (background thread), then post `webView.loadUrl` via `mainHandler.post`

### DO NOT modify:
- `HermesWebView.kt`
- `WebViewInjectors.kt`
- `HermesPreferences.kt`
- Any existing logic outside of `onPageFinished` and the new function

## Constraints

- Keep new strings in Kotlin only (no strings.xml)
- After changes, build:
  ```
  export JAVA_HOME=/home/lu/Software/android-studio/jbr
  export ANDROID_HOME=/home/lu/Android/Sdk
  cd android && ./gradlew assembleDebug
  ```
- Fix any compile errors
- Commit: `git add -A && git commit -m "feat: intercept /hermes-login page with native credentials dialog"`
- Report DONE and build result
