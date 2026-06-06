package dev.hermes.mobile

import android.content.Context
class HermesPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedDashboardBase(): String? {
        val raw = prefs.getString(PREF_LAST_DASHBOARD_BASE, null) ?: return null
        val normalized = DashboardDiscoveryService.normalizeDashboardBase(raw)
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

    companion object {
        const val PREFS_NAME = "hermes_mobile_client"
        const val PREF_LAST_DASHBOARD_BASE = "last_dashboard_base"
        const val PREF_TEXT_ZOOM = "text_zoom"
        const val STATE_WEBVIEW = "state_webview"
    }
}
