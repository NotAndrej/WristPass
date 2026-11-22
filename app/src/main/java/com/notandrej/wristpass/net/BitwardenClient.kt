package com.notandrej.wristpass.net

import com.notandrej.wristpass.data.ApiException
import com.notandrej.wristpass.data.PreloginInfo
import com.notandrej.wristpass.data.SessionTokens
import com.notandrej.wristpass.data.TwoFactorRequiredException
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The three Bitwarden REST calls WristPass makes. Both the official Bitwarden
 * cloud and a self-hosted Vaultwarden answer these the same way.
 */
class BitwardenClient(private val http: OkHttpClient) {

    /** `POST {server}/identity/accounts/prelogin` -> the account's KDF parameters. */
    fun prelogin(server: String, email: String): PreloginInfo {
        val body = JSONObject().put("email", email).toString().toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url("${base(server)}/identity/accounts/prelogin")
            .post(body)
            .build()
        val json = http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw parseError(response.code, text)
            runCatching { JSONObject(text) }.getOrElse { throw ApiException(response.code, "Malformed prelogin response") }
        }
        // Vaultwarden also nests these under "kdfSettings"; fall back to it.
        val settings = json.optJSONObject("kdfSettings")
        return PreloginInfo(
            kdf = json.optInt("kdf", settings?.optInt("kdfType") ?: 0),
            kdfIterations = json.optInt("kdfIterations", settings?.optInt("iterations") ?: 600_000),
            kdfMemory = json.optIntOrNull("kdfMemory") ?: settings?.optIntOrNull("memory"),
            kdfParallelism = json.optIntOrNull("kdfParallelism") ?: settings?.optIntOrNull("parallelism"),
        )
    }

    /**
     * `POST {server}/identity/connect/token`, form-encoded password grant.
     * [masterPasswordHash] is the base64 PBKDF2-of-the-master-key value, never the
     * plain master password.
     */
    fun login(
        server: String,
        email: String,
        masterPasswordHash: String,
        deviceIdentifier: String,
        twoFactorCode: String?,
        twoFactorProvider: Int?,
    ): SessionTokens {
        val form = FormBody.Builder()
            .add("grant_type", "password")
            .add("username", email)
            .add("password", masterPasswordHash)
            .add("scope", "api offline_access")
            .add("client_id", "mobile")
            .add("deviceType", "0") // Bitwarden DeviceType.Android
            .add("deviceIdentifier", deviceIdentifier)
            .add("deviceName", "WristPass")
            .apply {
                if (!twoFactorCode.isNullOrBlank()) {
                    add("twoFactorCode", twoFactorCode)
                    add("twoFactorProvider", (twoFactorProvider ?: 0).toString())
                    add("twoFactorRemember", "0")
                }
            }
            .build()
        val request = Request.Builder()
            .url("${base(server)}/identity/connect/token")
            .post(form)
            .build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw parseError(response.code, text)
            parseTokenResponse(response.code, text)
        }
    }

    /** `grant_type=refresh_token`, so a returning user keeps their session. */
    fun refresh(server: String, refreshToken: String): SessionTokens {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", "mobile")
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder()
            .url("${base(server)}/identity/connect/token")
            .post(form)
            .build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw parseError(response.code, text)
            parseTokenResponse(response.code, text)
        }
    }

    /** `GET {server}/api/sync` — profile, ciphers and folders, all still encrypted. */
    fun sync(server: String, accessToken: String): JSONObject {
        val request = Request.Builder()
            .url("${base(server)}/api/sync")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw parseError(response.code, text)
            runCatching { JSONObject(text) }.getOrElse { throw ApiException(response.code, "Malformed sync response") }
        }
    }

    private fun parseTokenResponse(code: Int, text: String): SessionTokens {
        val json = runCatching { JSONObject(text) }
            .getOrElse { throw ApiException(code, "Malformed token response") }
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw ApiException(code, "Response had no access token")
        val expiresIn = json.optLong("expires_in", 3600L)
        // The wrapped user key is "Key" on Bitwarden/Vaultwarden; tolerate "key" too.
        val protectedKey = json.optString("Key").takeIf { it.isNotBlank() && it != "null" }
            ?: json.optString("key").takeIf { it.isNotBlank() && it != "null" }
        return SessionTokens(
            accessToken = access,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() && it != "null" },
            expiresAtEpochMs = System.currentTimeMillis() + expiresIn * 1000L,
            protectedUserKey = protectedKey,
        )
    }

    /** Turns a non-2xx body into a typed exception, recognising 2FA challenges. */
    private fun parseError(code: Int, body: String): Exception {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val description = sequenceOf("error_description", "message", "Message", "error")
            .mapNotNull { json?.optString(it)?.takeIf { s -> s.isNotBlank() && s != "null" } }
            .firstOrNull()

        val providers = json?.optJSONArray("TwoFactorProviders")?.let { array ->
            (0 until array.length()).map { array.optInt(it).toString() }
        }.orEmpty()
        val mentionsTwoFactor = description?.contains("two factor", ignoreCase = true) == true ||
            description?.contains("2fa", ignoreCase = true) == true || providers.isNotEmpty()
        if (mentionsTwoFactor) {
            return TwoFactorRequiredException(providers, description ?: "Two-factor authentication required")
        }

        val invalidCredentials = code == 400 && (
            description?.contains("username or password", ignoreCase = true) == true ||
                description?.contains("invalid_grant", ignoreCase = true) == true
            )
        return ApiException(code, description ?: "Request failed (HTTP $code)", invalidCredentials)
    }

    private fun base(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        require(trimmed.toHttpUrlOrNull() != null) { "Enter a valid server URL" }
        return trimmed
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (isNull(name)) null else optInt(name)

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
