# Task: Auto-Login / Saved Credentials for Hermes Mobile App

## Background & Analysis

The app is a thin Android WebView shell around the Hermes dashboard (wg-hermes-proxy).

### Current auth flow
1. App loads connection hub → user picks a dashboard URL → WebView loads `<base>/chat`
2. If wg-hermes-proxy is the backend, the server serves a login page at `/hermes-login`
   - POST form with fields: `username`, `password`
   - On success: sets cookie `hermes_session` (30-day TTL in default config)
   - On fail: 401 + re-render login page
3. The app currently stores NOTHING about credentials — only the base URL via `HermesPreferences`

### Goal: Seamless / silent auto-login
When the user opens the app and connects to a saved base URL:
1. If we have stored credentials for that URL → auto-submit them to `/hermes-login` via HTTP POST (not via WebView form) before loading `/chat`
2. Set the resulting `hermes_session` cookie into the WebView's `CookieManager`
3. Then load `<base>/chat` — the user lands directly in the chat with no login page visible
4. If auto-login fails (wrong creds, server not reachable) → fall through to normal WebView load (user sees the login form as before)

The credentials dialog (step to save new creds) should appear when:
- User manually enters a new URL (the "Paste connector URL" flow)
- User taps a dedicated "Edit credentials" button in the menu

### Files to touch

- `android/app/src/main/java/dev/hermes/mobile/HermesPreferences.kt` — add save/load/clear for (username, password) per base URL, stored in **EncryptedSharedPreferences** (androidx.security:security-crypto)
- `android/app/src/main/java/dev/hermes/mobile/MainActivity.kt` — add auto-login logic before `loadUrl`, add credential dialog, add "Edit credentials" menu item in the existing menu/power button area
- `android/app/build.gradle` — add `androidx.security:security-crypto:1.1.0-alpha06` dependency

---

## Implementation Spec

### 1. `HermesPreferences.kt` — Credential storage

Add using **EncryptedSharedPreferences** (MasterKey + AES256_SIV / AES256_GCM):

```kotlin
// New functions to add to HermesPreferences:

fun saveCredentials(baseUrl: String, username: String, password: String)
fun loadCredentials(baseUrl: String): Pair<String, String>?  // null if none saved
fun clearCredentials(baseUrl: String)
```

Key scheme: use `"cred_user_<md5-of-baseUrl>"` and `"cred_pass_<md5-of-baseUrl>"` as pref keys so each URL has independent credentials.

Use a separate EncryptedSharedPreferences file named `"hermes_credentials"` (separate from the existing prefs file to avoid migration issues).

### 2. `MainActivity.kt` — Auto-login logic

Add a new private suspend fun (or run on startupExecutor):

```kotlin
// Returns true if auto-login succeeded and cookie was set
private fun attemptAutoLogin(baseUrl: String): Boolean {
    val (username, password) = hermesPreferences.loadCredentials(baseUrl) ?: return false
    return try {
        val loginUrl = "$baseUrl/hermes-login"
        val body = "username=${Uri.encode(username)}&password=${Uri.encode(password)}"
        val conn = URL(loginUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("User-Agent", "HermesAgentMobile/0.1")
        conn.doOutput = true
        conn.instanceFollowRedirects = false   // IMPORTANT: don't auto-follow redirect
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.outputStream.write(body.toByteArray())
        conn.outputStream.flush()
        val status = conn.responseCode
        if (status == 302 || status == 200) {
            // Extract Set-Cookie header and inject into CookieManager
            conn.headerFields["Set-Cookie"]?.forEach { cookieHeader ->
                CookieManager.getInstance().setCookie(baseUrl, cookieHeader)
            }
            CookieManager.getInstance().flush()
            true
        } else {
            false
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Auto-login failed: $e")
        false
    }
}
```

**Call site:** In `connectToDashboard(baseUrl)` (or the equivalent function that resolves a base URL and calls `webView.loadUrl(...)`) — before `webView.loadUrl(...)`, call `attemptAutoLogin(baseUrl)` on the background executor. Whether it succeeds or fails, proceed to load the URL.

**NOTE:** `attemptAutoLogin` must run off the main thread (already in `startupExecutor`). The `webView.loadUrl()` call must run on the main thread (via `mainHandler.post`).

### 3. Credentials dialog — show when entering a new URL

In the `handleManualUrlEntry()` flow (or wherever the user finishes typing/pasting a URL and it gets saved), after saving the base URL, show a dialog:

```
Title: "Save login credentials?"
Message: "Enter username and password to log in automatically next time."
Fields: EditText username, EditText password (inputType=textPassword)
Buttons:
  [Save] → call hermesPreferences.saveCredentials(baseUrl, username, password)
  [Skip] → do nothing (user will log in manually through web form)
```

The dialog should pre-fill username/password from any existing saved credentials for that URL.

### 4. "Edit credentials" in the menu

The app has a "power button" that shows a menu with logout/reset. Add a new item:

```
"Edit credentials" → show the same credentials dialog (pre-filled with saved values)
```

If the user clears both fields and taps Save → call `clearCredentials(baseUrl)`.

### 5. Logout behavior

The existing logout already clears cookies. Also call `hermesPreferences.clearCredentials(currentBase)` during logout so stale credentials don't auto-login after the user logs out intentionally.

---

## Constraints

- Do NOT modify `HermesWebView.kt` or `WebViewInjectors.kt`
- Do NOT change Hermes WebUI source code
- Do NOT refactor unrelated parts of MainActivity
- The dependency `androidx.security:security-crypto:1.1.0-alpha06` requires minSdk 23 (already minSdk 26 ✓)
- EncryptedSharedPreferences: use `MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()` and `EncryptedSharedPreferences.create(context, "hermes_credentials", masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)`
- Keep all new strings in Kotlin (no strings.xml needed)
- After all changes, build with:
  ```
  export JAVA_HOME=/home/lu/Software/android-studio/jbr
  export ANDROID_HOME=/home/lu/Android/Sdk
  cd android && ./gradlew assembleDebug
  ```
- Fix any compile errors. Report DONE and build result when complete.
- Commit: `git add -A && git commit -m "feat: save login credentials and auto-login on reconnect"`
