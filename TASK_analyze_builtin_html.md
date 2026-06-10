# Task: 分析所有内置 HTML 及 WebView 返回栈问题

## 背景

用户发现一个 bug：在 WebView 返回栈中，最后一个页面是：

```
Hermes Mobile Client
Opening Hermes dashboard...
Attempted endpoints:
```

这是一个"连接中/失败"的内置 HTML 提示页面。问题是：**如果已经成功连上了 dashboard，这个页面不应该出现在返回栈中**（用户按返回键还能回到这个页面，体验不对）。

## 你的任务（只分析，不改代码）

请完整阅读整个项目的 Kotlin/Java 源码，重点关注：

### 1. 找出所有"内置 HTML"
即代码中直接用 `loadData(...)` 或 `loadDataWithBaseURL(...)` 加载的 HTML 字符串。
列出：
- 每处内置 HTML 的大致内容（标题/用途）
- 所在文件名 + 行号
- 触发时机（什么情况下会加载这段 HTML）

### 2. 分析返回栈行为
Android WebView 的返回栈：
- `loadUrl(url)` 会把 URL 压入 WebView 内部的 back stack
- `loadData(...)` 同样会压入 back stack
- 只有 `webView.clearHistory()` 才能清空 back stack

请分析：
- 上述每处内置 HTML 加载后，是否有调用 `clearHistory()` 或等效操作？
- 连接成功后（实际 dashboard URL 加载完成），之前的内置 HTML 是否还留在 back stack 里？
- 用户按返回键时，`onBackPressed` / `canGoBack()` 的处理逻辑是什么？

### 3. 判断合理性
针对每处内置 HTML，判断：
- 它在返回栈中的存在是否合理？
- 如果不合理，原因是什么？

## 输出格式

请输出结构化的 markdown 分析报告，写入文件 `TASK_analyze_builtin_html_result.md`（与本文件同目录）。

不要修改任何代码。只分析、只写报告。
