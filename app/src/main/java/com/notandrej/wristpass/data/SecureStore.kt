package com.notandrej.wristpass.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * The only thing WristPass persists: the server URL, the account's email, the
 * device identifier the server knows us by, and the OAuth token pair. All of it
 * lives in an [EncryptedSharedPreferences] file whose key is held by the Android
 * Keystore, so it is unreadable without unlocking the watch.
 *
 * The master password, the derived master key, the user key and every decrypted
 * field are never written to disk.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "wristpass_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var server: String?
        get() = prefs.getString(KEY_SERVER, null)
        set(value) = prefs.edit().putString(KEY_SERVER, value).apply()

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) = prefs.edit().putString(KEY_ACCESS, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) = prefs.edit().putString(KEY_REFRESH, value).apply()

    var expiresAtEpochMs: Long
        get() = prefs.getLong(KEY_EXPIRES, 0L)
        set(value) = prefs.edit().putLong(KEY_EXPIRES, value).apply()

    /** Purely informational — the account's KDF as last seen, so the login form can say so. */
    var lastKdfSummary: String?
        get() = prefs.getString(KEY_KDF, null)
        set(value) = prefs.edit().putString(KEY_KDF, value).apply()

    /**
     * The account's KDF parameters, cached so a returning session can re-derive the
     * master key without waiting for a prelogin round trip. Not secret (they are
     * readable from the server by anyone who knows the email), and re-fetched on
     * every login anyway.
     */
    fun cacheKdf(kdf: Int, iterations: Int, memory: Int?, parallelism: Int?) {
        prefs.edit()
            .putInt(KEY_KDF_TYPE, kdf)
            .putInt(KEY_KDF_ITER, iterations)
            .putInt(KEY_KDF_MEMORY, memory ?: -1)
            .putInt(KEY_KDF_PARALLELISM, parallelism ?: -1)
            .apply()
    }

    fun cachedKdf(): com.notandrej.wristpass.data.PreloginInfo? {
        if (!prefs.contains(KEY_KDF_TYPE)) return null
        return com.notandrej.wristpass.data.PreloginInfo(
            kdf = prefs.getInt(KEY_KDF_TYPE, 0),
            kdfIterations = prefs.getInt(KEY_KDF_ITER, 600_000),
            kdfMemory = prefs.getInt(KEY_KDF_MEMORY, -1).takeIf { it >= 0 },
            kdfParallelism = prefs.getInt(KEY_KDF_PARALLELISM, -1).takeIf { it >= 0 },
        )
    }

    /** The opt-in "accept invalid TLS certificate" switch, for self-hosted servers. */
    var allowInvalidTls: Boolean
        get() = prefs.getBoolean(KEY_INVALID_TLS, false)
        set(value) = prefs.edit().putBoolean(KEY_INVALID_TLS, value).apply()

    /** Stable per-install identifier the server uses to remember this device (2FA, sessions). */
    val deviceIdentifier: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    fun saveSession(server: String, email: String, tokens: SessionTokens) {
        prefs.edit()
            .putString(KEY_SERVER, server)
            .putString(KEY_EMAIL, email)
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_EXPIRES, tokens.expiresAtEpochMs)
            .apply()
    }

    /** Drops the tokens but keeps the server/email so the next login is pre-filled. */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_EXPIRES)
            .apply()
    }

    fun hasSession(): Boolean = !accessToken.isNullOrBlank()

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_EMAIL = "email"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_DEVICE_ID = "device_identifier"
        const val KEY_KDF = "kdf_summary"
        const val KEY_KDF_TYPE = "kdf_type"
        const val KEY_KDF_ITER = "kdf_iterations"
        const val KEY_KDF_MEMORY = "kdf_memory"
        const val KEY_KDF_PARALLELISM = "kdf_parallelism"
        const val KEY_INVALID_TLS = "allow_invalid_tls"
    }
}
