package dev.hermes.mobile

object HermesConfig {
    const val HERMES_DEFAULT_PORT = 9119

    const val ENDPOINT_CHAT = "/chat"
    const val ENDPOINT_API_STATUS = "/api/status"

    const val CONNECT_TIMEOUT_MS = 2500
    const val READ_TIMEOUT_MS = 2500
    const val WARMUP_TIMEOUT_MS = 800
    const val DISCOVERY_CONNECT_TIMEOUT_MS = 1200

    const val TEXT_ZOOM_MIN = 60
    const val TEXT_ZOOM_MAX = 160
    const val TEXT_ZOOM_DEFAULT = 90
    const val DEFAULT_TEXT_ZOOM = TEXT_ZOOM_DEFAULT

    const val UA_SUFFIX = "HermesAgentMobile/0.1"
    const val HERMES_STATUS_BAR_COLOR = "#031615"

    const val QUICK_KEYS_BAR_HEIGHT_DP = 44
    const val QUICK_KEYS_HORIZONTAL_PADDING_DP = 8
    const val QUICK_KEY_HORIZONTAL_PADDING_DP = 14
    const val QUICK_KEY_VERTICAL_PADDING_DP = 10
    const val QUICK_KEY_MARGIN_END_DP = 4

    const val JS_BRIDGE_FOCUS = "HermesFocusBridge"
    const val JS_BRIDGE_INPUT = "HermesMobileNativeInput"

    object QuickKeyColors {
        const val TEXT = "#ffe6cb"
        const val BAR_BACKGROUND = "#061816"
        const val PRESSED_BACKGROUND = "#173a34"
        const val FOCUSED_BACKGROUND = "#173a34"
        const val DEFAULT_BACKGROUND = "#0d2420"
    }

    object AppScheme {
        const val SCHEME = "hermes"
        const val PREFIX = "$SCHEME://"

        const val HOST_DISCOVER = "discover"
        const val HOST_MANUAL = "manual"
        const val HOST_SAVED = "saved"
        const val HOST_SCRIPT = "script"
        const val HOST_MENU = "menu"
        const val HOST_TEXT_SIZE = "textsize"
        const val HOST_CONNECTOR = "connector"
        const val HOST_RELOAD_TUI = "reloadtui"

        const val URL_DISCOVER = "$PREFIX$HOST_DISCOVER"
        const val URL_MANUAL = "$PREFIX$HOST_MANUAL"
        const val URL_SAVED = "$PREFIX$HOST_SAVED"
        const val URL_SCRIPT = "$PREFIX$HOST_SCRIPT"
        const val URL_MENU = "$PREFIX$HOST_MENU"
        const val URL_TEXT_SIZE = "$PREFIX$HOST_TEXT_SIZE"
        const val URL_CONNECTOR = "$PREFIX$HOST_CONNECTOR"
        const val URL_RELOAD_TUI = "$PREFIX$HOST_RELOAD_TUI"
    }
}
