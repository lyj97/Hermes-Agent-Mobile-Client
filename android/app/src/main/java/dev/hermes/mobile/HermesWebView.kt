package dev.hermes.mobile

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

class HermesWebView(context: Context) : WebView(context) {
    interface MobileInputSink {
        fun sendText(text: String)
        fun sendKey(key: String)
    }

    var mobileInputSink: MobileInputSink? = null

    // Tracks whether the xterm terminal's hidden textarea is currently focused.
    // Set by HermesFocusBridge JS→Kotlin bridge via setXtermHelperTextareaFocused().
    // Default false = let WebView handle input natively (login, chat, etc.).
    @Volatile var xtermHelperTextareaFocused: Boolean = false

    // Let WebView's own logic decide whether this view is a text editor when xterm is not
    // focused. This ensures chat/login inputs get native IME without us interfering.
    override fun onCheckIsTextEditor(): Boolean =
        xtermHelperTextareaFocused || super.onCheckIsTextEditor()

    // Return type is nullable: super.onCreateInputConnection() (Java) can return null when
    // no editable DOM element is focused (e.g. tapping whitespace). Propagating null is
    // correct — it tells IME there is no active editor, which is the right behavior for
    // non-input taps. DO NOT replace null with BaseInputConnection: that creates a dead
    // sink that swallows all keystrokes without delivering them to the browser DOM.
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // Only hijack input for the xterm terminal when its hidden textarea is focused.
        // For all other elements (login inputs, chat box, etc.) fall back to the default
        // WebView InputConnection so the browser handles text entry natively.
        if (!xtermHelperTextareaFocused) {
            return super.onCreateInputConnection(outAttrs)  // may be null — propagate as-is
        }
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value.isNotEmpty()) mobileInputSink?.sendText(value)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val count = beforeLength.coerceAtLeast(1)
                repeat(count) { mobileInputSink?.sendKey("backspace") }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                return deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> mobileInputSink?.sendKey("backspace")
                    KeyEvent.KEYCODE_FORWARD_DEL -> mobileInputSink?.sendKey("delete")
                    KeyEvent.KEYCODE_ENTER -> mobileInputSink?.sendKey("enter")
                    KeyEvent.KEYCODE_DPAD_UP -> mobileInputSink?.sendKey("up")
                    KeyEvent.KEYCODE_DPAD_DOWN -> mobileInputSink?.sendKey("down")
                    KeyEvent.KEYCODE_DPAD_LEFT -> mobileInputSink?.sendKey("left")
                    KeyEvent.KEYCODE_DPAD_RIGHT -> mobileInputSink?.sendKey("right")
                    else -> event.unicodeChar.takeIf { it > 0 }?.let { mobileInputSink?.sendText(it.toChar().toString()) }
                }
                return true
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                mobileInputSink?.sendKey("enter")
                return true
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = super.dispatchTouchEvent(event)

    // All scrollable content inside Hermes WebUI uses internal JS overflow scroll (overflow-y:auto on divs).
    // WebView's own scroll position is always 0. Making scrollTo/scrollBy no-ops prevents the native
    // WebView gesture recogniser from producing a competing scroll that fights our JS touch→wheel bridge.
    override fun scrollTo(x: Int, y: Int) { /* no-op: page scroll is JS-internal */ }
    override fun scrollBy(x: Int, y: Int) { /* no-op: page scroll is JS-internal */ }
}
