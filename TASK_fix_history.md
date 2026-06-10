# Task: 修复 WebView 返回栈中残留的临时 status 页面

## 背景与问题

在 `loadDashboardBase()` 和 `reloadFreshTui()` 中，连接成功的路径是：
1. `renderStatusPage("Opening Hermes dashboard...", ...)` → `loadDataWithBaseURL(...)` 入栈
2. 后台 warmup / auto-login
3. `webView.loadUrl(chatUrl)` 入栈

步骤 1 和 3 之间没有 `clearHistory()`，导致连接成功后，"Opening Hermes dashboard..." / "Opening fresh Hermes TUI..." 这类临时过渡页仍留在 WebView back stack。
用户按返回键会错误地回到这张过时的内置 status 页，体验不对。

## 修复目标

**只清除"临时过渡性"status页**，不要无差别 clearHistory：
- `Opening Hermes dashboard...`：连接成功后不应可回退
- `Opening fresh Hermes TUI...`：TUI 加载成功后不应可回退

**保留合理的返回栈条目**：
- Connection Home 页面（用户可能想回去换连接方式）
- 错误类 status 页（`Dashboard load failed`、`Could not find` 等）

## 修复方案

在 `onPageFinished` 回调中（MainActivity.kt，已有该回调），判断刚加载完的页面是否是"真实 dashboard"（即 URL 以 `http://` 或 `https://` 开头，且不是 `about:blank`）。

若是真实 dashboard 页面，则调用 `webView.clearHistory()`，清除此前所有的临时 status 页。

**注意事项：**
- `clearHistory()` 必须在主线程调用，`onPageFinished` 已在主线程，可直接调用
- `onPageFinished` 是在 `WebViewClient` 的匿名类中实现的，直接在那里加逻辑即可
- 只有在 `!showingConnectionHub`（即已切换到 dashboard 模式）时才清历史，以避免在 Connection Hub 内部导航时也清历史
- clearHistory 要在现有 injector 调用之后（或之前，不影响注入），只要在同一个 `onPageFinished` 里即可

## 具体改动

请阅读 `android/app/src/main/java/dev/hermes/mobile/MainActivity.kt`，找到 `onPageFinished` 的实现，在其中加入：

```kotlin
// 若已成功进入 dashboard（非 hub 内置页），清除临时过渡页历史
if (!showingConnectionHub) {
    webView.clearHistory()
}
```

位置：在 `onPageFinished` 的 `if (!showingConnectionHub)` 代码块内，已有 injector 调用之后（最后一行注入之后，`mainHandler.postDelayed(...)` 之前或之后均可）。

## 额外检查

检查一下 `onBackPressed` 的逻辑：
- 如果当前正在显示 Connection Home（`showingConnectionHub == true`），按返回键时应直接 `finish()`，不应 `goBack()`（因为 hub 内的历史都是内置 HTML，没有必要在里面 goBack）
- 如果当前在 dashboard（`showingConnectionHub == false`），按返回键时可以 `canGoBack()` 判断是否能在 dashboard 内回退（但由于前面 clearHistory 了，通常 `canGoBack()` 会是 false，直接 finish）

如果 `onBackPressed` 的逻辑和上面描述不一致，也一并修复。

## 构建验证

改完后构建：
```bash
cd android && export JAVA_HOME=/path/to/android-studio/jbr && export ANDROID_HOME=/path/to/Android/Sdk && ./gradlew assembleDebug 2>&1 | tail -20
```

构建成功（BUILD SUCCESSFUL）后 commit：
```bash
git add -A && git commit -m "fix: clear WebView history after dashboard loads to remove transient status pages from back stack"
```

如果构建失败，修复编译错误后再 commit。

报告：
1. 最终改动的代码（diff 或修改内容描述）
2. 构建结果
3. commit hash
