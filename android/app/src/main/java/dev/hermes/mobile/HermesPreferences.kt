package dev.hermes.mobile

import android.content.Context
import java.net.URL

class HermesPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedDashboardBase(): String? {
        val raw = prefs.getString(PREF_LAST_DASHBOARD_BASE, null) ?: return null
        val normalized = normalizeDashboardBase(raw)
        if (normalized.isNotBlank() && normalized != raw) {
            prefs.edit().putString(PREF_LAST_DASHBOARD_BASE, normalized).apply()
        }
        return normalized.ifBlank { null }
    }

    fun saveDashboardBase(base: String) {
        prefs.edit()
            .putString(PREF_LAST_DASHBOARD_BASE, base)
            .apply()
    }

    fun clearDashboardBase() {
        prefs.edit().clear().apply()
    }

    fun getSavedTextZoom(): Int {
        return prefs
            .getInt(PREF_TEXT_ZOOM, HermesConfig.DEFAULT_TEXT_ZOOM)
            .coerceIn(HermesConfig.TEXT_ZOOM_MIN, HermesConfig.TEXT_ZOOM_MAX)
    }

    fun saveTextZoom(zoom: Int) {
        prefs.edit()
            .putInt(PREF_TEXT_ZOOM, zoom)
            .apply()
    }

    private fun normalizeDashboardBase(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return try {
            val url = URL(withScheme)
            val host = url.host
            if (host.isNullOrBlank()) return ""
            val protocol = if (url.protocol.equals("https", ignoreCase = true)) "https" else "http"
            val port = if (url.port > 0) ":${url.port}" else ""
            "$protocol://$host$port"
        } catch (_: Exception) {
            withScheme
                .substringBefore("?")
                .substringBefore("#")
                .removeSuffix("/")
                .removeSuffix(HermesConfig.ENDPOINT_CHAT)
                .removeSuffix("/")
        }
    }

    companion object {
        const val PREFS_NAME = "hermes_mobile_client"
        const val PREF_LAST_DASHBOARD_BASE = "last_dashboard_base"
        const val PREF_TEXT_ZOOM = "text_zoom"
        const val STATE_WEBVIEW = "state_webview"
    }
}
