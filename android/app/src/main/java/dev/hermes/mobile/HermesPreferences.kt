package dev.hermes.mobile

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

class HermesPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val credentialPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            CREDENTIALS_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

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

    fun saveCredentials(baseUrl: String, username: String, password: String) {
        val key = credentialKey(baseUrl)
        credentialPrefs.edit()
            .putString("cred_user_$key", username)
            .putString("cred_pass_$key", password)
            .apply()
    }

    fun loadCredentials(baseUrl: String): Pair<String, String>? {
        val key = credentialKey(baseUrl)
        val username = credentialPrefs.getString("cred_user_$key", null) ?: return null
        val password = credentialPrefs.getString("cred_pass_$key", null) ?: return null
        return username to password
    }

    fun clearCredentials(baseUrl: String) {
        val key = credentialKey(baseUrl)
        credentialPrefs.edit()
            .remove("cred_user_$key")
            .remove("cred_pass_$key")
            .apply()
    }

    private fun credentialKey(baseUrl: String): String {
        val normalized = DashboardDiscoveryService.normalizeDashboardBase(baseUrl)
        val bytes = MessageDigest.getInstance("MD5").digest(normalized.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PREFS_NAME = "hermes_mobile_client"
        const val CREDENTIALS_PREFS_NAME = "hermes_credentials"
        const val PREF_LAST_DASHBOARD_BASE = "last_dashboard_base"
        const val PREF_TEXT_ZOOM = "text_zoom"
        const val STATE_WEBVIEW = "state_webview"
    }
}
