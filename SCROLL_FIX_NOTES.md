# Terminal Scroll Fix — Design Notes

## 问题描述

xterm.js 只监听 `WheelEvent`，不处理 `TouchEvent`。
Android WebView 的触摸滑动以 `TouchEvent` 传递，xterm 的
`.xterm-viewport` / `.xterm-scrollable-element` 不响应，导致终端区域无法触摸滚动。
其他区域的滚动（原生 WebView 滚动）正常，只有 xterm 内部失效。

## 修复策略

**在 Android 侧注入 JS 桥（Touch → WheelEvent），不修改 Hermes WebUI 源码。**

原因：
- Hermes WebUI 是独立上游项目，不应为 Android 客户端做强耦合适配
- Android 端通过 `evaluateJavascript` 注入脚本是标准 WebView 扩展方式，解耦合理
- 注入脚本用 MutationObserver 自适应 xterm 挂载时机，无需依赖 WebUI 特定 class/attribute

## 实现方案（Android Only）

在 `MainActivity.kt` 的 `WebViewClient.onPageFinished` 中，调用 `evaluateJavascript` 注入以下逻辑：

1. **MutationObserver** 监听 DOM，等待 xterm 元素出现
2. 找到 `.xterm-scrollable-element`（优先）/ `.xterm-viewport` / `.xterm` 作为滚动目标
3. 在 xterm host 元素上安装：
   - `touchstart`（passive）：记录起始坐标
   - `touchmove`（passive: false）：计算 deltaX/deltaY，`preventDefault()`，
     向目标 dispatch `new WheelEvent('wheel', { deltaX, deltaY, deltaMode: DOM_DELTA_PIXEL, bubbles: true })`
   - `touchend` / `touchcancel`（passive）：重置状态
4. 对已安装的元素打标记（`__hermesTouchWheelInstalled = true`），防止重复安装
5. CSS 设置 `touch-action: none; overscroll-behavior: contain` 到 xterm 相关元素

## 选择器（健壮性降序）

```
.xterm-scrollable-element  ← 首选（xterm 内部滚动容器）
.xterm-viewport            ← 备选
.xterm                     ← 兜底
```

不依赖 Hermes WebUI 自定义 class（如 `hermes-terminal-host`），
选择器仅依赖 xterm.js 自身生成的 DOM 结构，与 WebUI 版本无关。

## 约束

- **不修改 Hermes WebUI 源码**（`~/.hermes/hermes-agent/`）
- 注入 JS 应在 `onPageFinished` 时执行，并在每次页面加载后重新注入
- 如果 WebUI 升级导致 xterm DOM 结构变化，只需更新 Android 侧选择器
