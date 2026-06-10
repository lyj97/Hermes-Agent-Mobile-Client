# 内置 HTML 与 WebView 返回栈分析报告

## 范围

已阅读项目内所有 Kotlin/Java 源码：

- `android/app/src/main/java/dev/hermes/mobile/MainActivity.kt`
- `android/app/src/main/java/dev/hermes/mobile/WebViewInjectors.kt`
- `android/app/src/main/java/dev/hermes/mobile/HermesWebView.kt`
- `android/app/src/main/java/dev/hermes/mobile/HermesConfig.kt`
- `android/app/src/main/java/dev/hermes/mobile/HermesPreferences.kt`
- `android/app/src/main/java/dev/hermes/mobile/DashboardDiscoveryService.kt`

结论：项目中直接通过 `loadData(...)` / `loadDataWithBaseURL(...)` 加载 HTML 字符串的地方只有 2 处，均在 `MainActivity.kt`。没有发现 `loadData(...)` 调用。

## 1. 所有内置 HTML

### A. Connection Home 页面

- 位置：`android/app/src/main/java/dev/hermes/mobile/MainActivity.kt:489`
- 加载调用：`android/app/src/main/java/dev/hermes/mobile/MainActivity.kt:569`
- API：`webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)`
- 大致内容：
  - 品牌文案：`Hermes Agent`
  - 主标题：`Connect your Hermes`
  - 入口按钮：
    - `Resume saved connection`
    - `Install Hermes Mobile Connector`
    - `Paste connector URL`
    - `Scan same Wi-Fi`
  - 说明文字：这是连接入口页，用于安装/复制 connector 命令、手动输入 URL、局域网扫描或恢复保存连接。
- 触发时机：
  - App 首次启动且没有保存的 dashboard base：`onCreate()` 中 `renderConnectionHome()`，见 `MainActivity.kt:277-282`。
  - 自动发现失败后：`bootstrapDashboardConnection()` 先显示失败状态页，再调用 `renderConnectionHome()`，见 `MainActivity.kt:603-605`。
  - 手动输入为空：`promptForManualEndpoint()` 中先 `renderStatusPage("Manual endpoint is empty.")`，再 `renderConnectionHome()`，见 `MainActivity.kt:749-752`。
  - 手动输入弹窗点 `Back`：见 `MainActivity.kt:754`。
  - 复制 connector 命令后：先显示状态页，再回到 home，见 `MainActivity.kt:794-799`。
  - connector 脚本弹窗点 `Back`：见 `MainActivity.kt:800`。
  - 没有保存 base 时执行 `reloadFreshTui()`：见 `MainActivity.kt:820-824`。
  - logout/reset 后：`logoutAndReset()` 清除历史和缓存后调用，见 `MainActivity.kt:927-937`。

### B. Status 页面

- 位置：`android/app/src/main/java/dev/hermes/mobile/MainActivity.kt:687`
- 加载调用：`android/app/src/main/java/dev/hermes/mobile/MainActivity.kt:702`
- API：`webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)`
- 大致内容：
  - 标题：`Hermes Mobile Client`
  - 动态消息，例如：
    - `Scanning local network for Hermes dashboard...`
    - `Opening Hermes dashboard...`
    - `Could not find Hermes dashboard automatically.`
    - `Connector URL is empty.`
    - `Dashboard load failed: ...`
    - `Dashboard returned HTTP ...`
    - `Opening fresh Hermes TUI...`
  - 固定区块：`Attempted endpoints:`
  - 提示：确保手机网络可以访问 Hermes dashboard 的默认端口。
- 触发时机：
  - 同 Wi-Fi 扫描开始：`bootstrapDashboardConnection()`，见 `MainActivity.kt:581-583`。
  - 自动发现失败：`bootstrapDashboardConnection()`，见 `MainActivity.kt:603-604`。
  - `loadDashboardBase()` 收到空/非法 base：见 `MainActivity.kt:609-614`。
  - 即将打开 dashboard `/chat` 前：`loadDashboardBase()`，见 `MainActivity.kt:619-625`。
  - WebView 主框架加载错误：`onReceivedError()`，见 `MainActivity.kt:185-197`。
  - WebView 主框架 HTTP 错误：`onReceivedHttpError()`，见 `MainActivity.kt:200-212`。
  - 手动 endpoint 为空：`promptForManualEndpoint()`，见 `MainActivity.kt:749-751`。
  - 复制 connector 命令后：`showVpsScriptDialog()`，见 `MainActivity.kt:794-798`。
  - 重新打开 fresh TUI 前：`reloadFreshTui()`，见 `MainActivity.kt:820-830`。

## 2. 返回栈行为分析

### clearHistory 使用情况

项目中只有一处 `webView.clearHistory()`：

- `MainActivity.kt:935`，位于 `logoutAndReset()`。
- 调用顺序是：
  - 清 credentials
  - 清 dashboard base
  - 清 cookies
  - `webView.clearHistory()`
  - `webView.clearCache(true)`
  - `renderConnectionHome()`

除 logout/reset 外，所有 `renderConnectionHome()` 和 `renderStatusPage()` 的 `loadDataWithBaseURL(...)` 后都没有 `clearHistory()` 或等效操作。

### 连接成功后的返回栈

典型成功路径在 `loadDashboardBase()`：

1. `showingConnectionHub = false`，见 `MainActivity.kt:619`。
2. 构造 `chatUrl = "$normalizedBase/chat"`，见 `MainActivity.kt:620`。
3. 调用 `renderStatusPage("Opening Hermes dashboard...", listOf(normalizedBase))`，见 `MainActivity.kt:621`。
4. 后台 warmup 和 auto-login。
5. `mainHandler.post { webView.loadUrl(chatUrl) }`，见 `MainActivity.kt:625`。

因为第 3 步的 `loadDataWithBaseURL(...)` 会进入 WebView 历史，第 5 步的 `loadUrl(chatUrl)` 也会进入 WebView 历史，并且两者之间没有 `clearHistory()`，所以连接成功后，`Opening Hermes dashboard...` 这张内置 status 页面会留在 WebView back stack 里。

如果是首次启动且存在保存的 dashboard base：

- `onCreate()` 直接调用 `loadDashboardBase(lastBase, persist = false)`，见 `MainActivity.kt:277-280`。
- 返回栈大致为：`Status: Opening Hermes dashboard...` -> `dashboard /chat`。
- 用户在 dashboard 按返回键时会回到 status 页面。

如果是从 Connection Home 点击 saved/manual/discover 后成功连接：

- 返回栈大致为：`Connection Home` -> `Status: Opening Hermes dashboard...` -> `dashboard /chat`。
- 用户按返回键会先回到 `Opening Hermes dashboard...`，再可能回到 Connection Home。

`reloadFreshTui()` 也有同类问题：

- 先 `renderStatusPage("Opening fresh Hermes TUI...", listOf(base))`，见 `MainActivity.kt:827`。
- 再延迟 `webView.loadUrl("$base/chat")`，见 `MainActivity.kt:828-830`。
- 中间同样没有清历史，因此 fresh TUI 成功打开后，这张 status 页面也会留在返回栈。

### onBackPressed / canGoBack 逻辑

返回键处理在 `MainActivity.kt:258-266`：

```kotlin
onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }
})
```

逻辑非常直接：

- 只要 WebView 内部历史可回退，就调用 `webView.goBack()`。
- 没有过滤内置 HTML。
- 没有判断当前是否已经在 dashboard。
- 没有针对 `Opening Hermes dashboard...` 或其它 status page 做跳过。
- 如果不能回退，才 `finish()` 退出 Activity。

另外，`onSaveInstanceState()` / `restoreState()` 会保存和恢复 WebView 状态，见 `MainActivity.kt:268-274` 和 `MainActivity.kt:482-486`。因此配置变化后，包含内置 HTML 的历史栈也可能被恢复。

## 3. 合理性判断

### Connection Home 页面

判断：作为可回退页面通常合理，但存在重复入栈风险。

合理的情况：

- 首次启动无保存连接时，它是主入口页面，作为 WebView 当前页存在合理。
- 用户从 home 进入 dashboard 后，是否允许返回 home 取决于产品预期；如果把 home 当作连接选择页，保留它有一定合理性。
- logout/reset 中先 `clearHistory()` 再显示 home 是合理的，这会避免用户回到已登录 dashboard 或旧状态页。

不合理/可疑的情况：

- 多处错误分支会先 `renderStatusPage(...)` 再 `renderConnectionHome()`，例如自动发现失败、手动 endpoint 为空、复制 connector 后。这会让 status 和 home 都进入历史。
- 重复调用 `renderConnectionHome()` 不会清理或替换旧的 home/status 历史，用户可能返回到过期的提示页。

### Status 页面

判断：失败结果页留在返回栈有时合理；连接中/临时过渡页在成功后留在返回栈不合理。

合理的情况：

- `Dashboard load failed: ...` 或 `Dashboard returned HTTP ...` 这类最终错误页作为当前页面展示，短期内留在返回栈中可以理解，因为用户可能需要查看错误信息或返回之前的连接入口。
- `Could not find Hermes dashboard automatically.` 这类失败提示页如果作为用户可读结果页，也可以保留。

不合理的情况：

- `Opening Hermes dashboard...` 是连接中的临时过渡页。成功加载实际 dashboard 后，它不再代表当前状态。
- `Opening fresh Hermes TUI...` 同样是临时过渡页。fresh TUI 成功加载后，用户不应返回到这张页面。
- 这些临时 status 页包含“Attempted endpoints”等连接过程信息。dashboard 已成功打开时，按返回键回到它会造成“已经成功但又显示连接中/失败提示”的错误体验。
- 当前实现没有在 dashboard `onPageFinished()` 成功后调用 `clearHistory()`，也没有在 `loadUrl(chatUrl)` 前后替换该过渡页，因此它必然可能残留在返回栈。

## 4. 对当前 bug 的直接结论

用户看到的返回栈最后页面：

```text
Hermes Mobile Client
Opening Hermes dashboard...
Attempted endpoints:
```

对应 `renderStatusPage("Opening Hermes dashboard...", listOf(normalizedBase))`，位置是 `MainActivity.kt:621`，HTML 定义和加载在 `MainActivity.kt:687-702`。

该页面在 `loadDashboardBase()` 中先通过 `loadDataWithBaseURL(...)` 入栈，随后 dashboard `/chat` 通过 `loadUrl(...)` 入栈。成功后没有任何 `clearHistory()` 或跳过逻辑，所以它会留在 WebView back stack 中。返回键处理只看 `webView.canGoBack()`，因此用户按返回键时会正常 `goBack()` 回到这张内置 status 页面。

因此，bug 判断成立：如果 dashboard 已成功加载，`Opening Hermes dashboard...` 这类连接中临时页面不应继续作为可返回页面保留在 WebView 返回栈中。
