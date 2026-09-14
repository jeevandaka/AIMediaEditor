package com.aimediaeditor.app.data.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds the user's own Hugging Face access token -- used for exactly ONE thing:
 * authenticating the ONE-TIME download of the on-device Gemma model file
 * ([com.aimediaeditor.app.ai.LocalLlmModelManager]), since Google's `.task` model
 * repository is gated behind a Hugging Face account and the Gemma license. Formerly
 * this class (`ApiKeyStore`) held an Anthropic API key sent with every AI edit
 * request to a cloud API; now that the whole AI layer runs on-device, there is no
 * per-request credential at all -- this token is used once, for the download, and
 * never touched again afterward.
 *
 * [EncryptedSharedPreferences] (Google's own AndroidX library, backed by the Android
 * Keystore) rather than hand-rolled `Cipher`/`KeyStore` calls -- rolling your own
 * crypto wiring is a much easier way to get this subtly wrong than adding one
 * well-known, first-party dependency for exactly this purpose. Kept even though the
 * token is only needed once (rather than, say, only holding it in memory for the
 * download and discarding it) so a download that gets interrupted (killed app, lost
 * connectivity partway through a multi-hundred-MB transfer) can resume without asking
 * the user to paste their token in again.
 */
class ModelAccessTokenStore(context: Context) {

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

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun hasToken(): Boolean = getToken() != null

    companion object {
        private const val PREFS_FILE_NAME = "model_access_token_prefs"
        private const val KEY_TOKEN = "huggingface_token"
    }
}
