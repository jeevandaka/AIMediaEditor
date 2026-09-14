package com.aimediaeditor.app.data.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds the user's own Anthropic API key -- the README's stated plan since before any
 * AI code existed: "a securely-stored key (never bundled into the APK)". Nothing in
 * this app ever ships with a key baked in; the user pastes their own into a settings
 * screen, and this is where it lives after that.
 *
 * [EncryptedSharedPreferences] (Google's own AndroidX library, backed by the Android
 * Keystore) rather than hand-rolled `Cipher`/`KeyStore` calls -- rolling your own
 * crypto wiring is a much easier way to get this subtly wrong than adding one
 * well-known, first-party dependency for exactly this purpose.
 */
class ApiKeyStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getKey(): String? = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun setKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clearKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    fun hasKey(): Boolean = getKey() != null

    companion object {
        private const val PREFS_FILE_NAME = "ai_api_key_prefs"
        private const val KEY_API_KEY = "anthropic_api_key"
    }
}
