package com.notandrej.wristpass.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notandrej.wristpass.crypto.Crypto
import com.notandrej.wristpass.crypto.Totp
import com.notandrej.wristpass.data.ApiException
import com.notandrej.wristpass.data.PreloginInfo
import com.notandrej.wristpass.data.RawCipher
import com.notandrej.wristpass.data.SecureStore
import com.notandrej.wristpass.data.SessionTokens
import com.notandrej.wristpass.data.TwoFactorRequiredException
import com.notandrej.wristpass.data.VaultItem
import com.notandrej.wristpass.net.BitwardenClient
import com.notandrej.wristpass.net.HttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class Stage { LOGIN, VAULT, SETTINGS }

/**
 * Owns the whole lifecycle: credentials -> master key -> token -> sync ->
 * decrypted items. Everything secret is kept in memory only and dropped by
 * [lock], which MainActivity calls whenever the app leaves the foreground.
 */
class VaultViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SecureStore(app)

    private var client = BitwardenClient(HttpClientFactory.create(store.allowInvalidTls))
    private var clientAllowsInvalidTls = store.allowInvalidTls

    // ---- login form ----
    var server by mutableStateOf(store.server ?: DEFAULT_SERVER)
    var email by mutableStateOf(store.email ?: "")
    var masterPassword by mutableStateOf("")
    var twoFactorCode by mutableStateOf("")
    var needsTwoFactor by mutableStateOf(false)
    var hasSavedSession by mutableStateOf(store.hasSession())
    var kdfSummary by mutableStateOf(store.lastKdfSummary)

    // ---- global ----
    var stage by mutableStateOf(Stage.LOGIN)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var allowInvalidTls by mutableStateOf(store.allowInvalidTls)

    // ---- vault ----
    var items by mutableStateOf<List<VaultItem>>(emptyList())
    var query by mutableStateOf("")
    var selected by mutableStateOf<VaultItem?>(null)

    val filteredItems: List<VaultItem>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return items
            return items.filter {
                it.name.contains(q, ignoreCase = true) ||
                    (it.username?.contains(q, ignoreCase = true) ?: false)
            }
        }

    val canSubmit: Boolean
        get() = !loading && server.isNotBlank() && email.isNotBlank() && masterPassword.isNotEmpty()

    // ---------------------------------------------------------------- actions

    /**
     * Unlock (or first log in). Always requires the master password, because the
     * user key is wrapped with the key derived from it — a cached token is enough
     * to *reach* the vault, never to decrypt it.
     */
    fun unlock() {
        if (!canSubmit) return
        viewModelScope.launch {
            loading = true
            error = null
            status = null
            try {
                val srv = normalizeServer(server)
                val normalizedEmail = email.trim().lowercase()

                status = "Deriving master key…"
                val pre = fetchKdf(srv, normalizedEmail)
                val masterKey = deriveMasterKey(masterPassword, normalizedEmail, pre)
                val masterPasswordHash = masterPasswordHash(masterKey, masterPassword)

                status = "Signing in…"
                val tokens = obtainTokens(srv, normalizedEmail, masterPasswordHash)
                store.saveSession(srv, normalizedEmail, tokens)
                store.cacheKdf(pre.kdf, pre.kdfIterations, pre.kdfMemory, pre.kdfParallelism)
                store.lastKdfSummary = kdfLabel(pre)
                kdfSummary = store.lastKdfSummary
                hasSavedSession = true

                status = "Syncing vault…"
                val sync = client.sync(srv, tokens.accessToken)
                val userKey = unwrapUserKey(sync, tokens, masterKey)

                status = "Decrypting…"
                val (encKey, macKey) = Crypto.toEncMac(userKey)
                val decrypted = withContext(Dispatchers.Default) {
                    decryptCiphers(sync, encKey, macKey)
                }

                items = decrypted.sortedBy { it.name.lowercase() }
                selected = null
                needsTwoFactor = false
                twoFactorCode = ""
                masterPassword = ""      // never keep the password around
                stage = Stage.VAULT
                status = null
            } catch (e: TwoFactorRequiredException) {
                needsTwoFactor = true
                error = "Two-factor code needed"
            } catch (e: Exception) {
                error = readable(e)
            } finally {
                loading = false
            }
        }
    }

    /** Re-lock: drop all key material and decrypted content, keep the token. */
    fun lock() {
        items = emptyList()
        selected = null
        query = ""
        masterPassword = ""
        twoFactorCode = ""
        needsTwoFactor = false
        error = null
        status = null
        stage = Stage.LOGIN
        hasSavedSession = store.hasSession()
    }

    fun signOut() {
        store.clearSession()
        hasSavedSession = false
        lock()
    }

    fun updateAllowInvalidTls(enabled: Boolean) {
        allowInvalidTls = enabled
        store.allowInvalidTls = enabled
        if (enabled != clientAllowsInvalidTls) {
            client = BitwardenClient(HttpClientFactory.create(enabled))
            clientAllowsInvalidTls = enabled
        }
    }

    fun select(item: VaultItem) { selected = item }
    fun closeDetail() { selected = null }

    /** TOTP for an item, or null when it has no usable seed. */
    fun totpParams(item: VaultItem): Totp.Params? =
        item.totpSeed?.let { runCatching { Totp.parse(it) }.getOrNull() }

    // -------------------------------------------------------------- internals

    private suspend fun fetchKdf(srv: String, normalizedEmail: String): PreloginInfo {
        // Offline-ish fallback: if prelogin cannot be reached but we have cached
        // parameters for this same account, use them rather than failing outright.
        return try {
            withContext(Dispatchers.IO) { client.prelogin(srv, normalizedEmail) }
        } catch (e: Exception) {
            store.cachedKdf() ?: throw e
        }
    }

    private suspend fun obtainTokens(
        srv: String,
        normalizedEmail: String,
        masterPasswordHash: String,
    ): SessionTokens = withContext(Dispatchers.IO) {
        if (store.hasSession() && store.expiresAtEpochMs > System.currentTimeMillis() + 60_000) {
            SessionTokens(store.accessToken!!, store.refreshToken, store.expiresAtEpochMs, null)
        } else if (!store.refreshToken.isNullOrBlank() && twoFactorCode.isBlank()) {
            runCatching { client.refresh(srv, store.refreshToken!!) }.getOrElse {
                client.login(
                    srv, normalizedEmail, masterPasswordHash, store.deviceIdentifier,
                    twoFactorCode.takeIf { it.isNotBlank() }, 0,
                )
            }
        } else {
            client.login(
                srv, normalizedEmail, masterPasswordHash, store.deviceIdentifier,
                twoFactorCode.takeIf { it.isNotBlank() }, 0,
            )
        }
    }

    /** `profile.key` (or the token's `Key`) unwrapped with the stretched master key. */
    private fun unwrapUserKey(sync: JSONObject, tokens: SessionTokens, masterKey: ByteArray): ByteArray {
        val protectedKey = sync.optJSONObject("profile")
            ?.optString("key")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: tokens.protectedUserKey
            ?: throw ApiException(500, "Server did not return a wrapped key")
        val (encKey, macKey) = Crypto.stretchKey(masterKey)
        return Crypto.decryptCipherString(protectedKey, encKey, macKey)
    }

    private fun decryptCiphers(sync: JSONObject, encKey: ByteArray, macKey: ByteArray): List<VaultItem> {
        val array = sync.optJSONArray("ciphers") ?: return emptyList()
        val result = ArrayList<VaultItem>(array.length())
        for (i in 0 until array.length()) {
            val cipher = array.optJSONObject(i) ?: continue
            if (cipher.optInt("type", -1) != 1) continue                          // logins only
            if (!cipher.isNull("deletedDate")) continue                            // skip trash
            val raw = RawCipher(
                id = cipher.optString("id"),
                type = 1,
                name = cipher.optStringOrNull("name"),
                key = cipher.optStringOrNull("key"),
                username = cipher.optJSONObject("login")?.optStringOrNull("username"),
                password = cipher.optJSONObject("login")?.optStringOrNull("password"),
                totp = cipher.optJSONObject("login")?.optStringOrNull("totp"),
                uri = cipher.optJSONObject("login")?.let { login ->
                    login.optJSONArray("uris")?.optJSONObject(0)?.optStringOrNull("uri")
                        ?: login.optStringOrNull("uri")
                },
                favorite = cipher.optBoolean("favorite", false),
                deleted = false,
            )
            result.add(decryptItem(raw, encKey, macKey))
        }
        return result
    }

    private fun decryptItem(raw: RawCipher, encKey: ByteArray, macKey: ByteArray): VaultItem {
        // Per-item key: unwrap it with the account key, then use it for the fields.
        val (fieldEnc, fieldMac) = raw.key?.let { wrapped ->
            Crypto.toEncMac(Crypto.decryptCipherString(wrapped, encKey, macKey))
        } ?: (encKey to macKey)

        fun decrypt(value: String?): String? {
            if (value.isNullOrBlank() || value == "null") return null
            return runCatching {
                Crypto.decryptCipherString(value, fieldEnc, fieldMac).toString(Charsets.UTF_8)
            }.getOrNull()
        }

        return VaultItem(
            id = raw.id,
            name = decrypt(raw.name) ?: "(untitled)",
            username = decrypt(raw.username),
            password = decrypt(raw.password),
            totpSeed = decrypt(raw.totp),
            uri = decrypt(raw.uri),
            favorite = raw.favorite,
        )
    }

    private fun deriveMasterKey(password: String, normalizedEmail: String, pre: PreloginInfo): ByteArray {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val salt = normalizedEmail.toByteArray(Charsets.UTF_8)
        return if (pre.isArgon2) {
            val memoryMb = pre.kdfMemory ?: 64
            val parallelism = pre.kdfParallelism ?: 4
            Crypto.argon2id(passwordBytes, salt, pre.kdfIterations, memoryMb * 1024, parallelism, 32)
        } else {
            Crypto.pbkdf2Sha256(passwordBytes, salt, pre.kdfIterations, 32)
        }
    }

    private fun masterPasswordHash(masterKey: ByteArray, password: String): String {
        val hash = Crypto.pbkdf2Sha256(masterKey, password.toByteArray(Charsets.UTF_8), 1, 32)
        return Crypto.b64Encode(hash)
    }

    private fun normalizeServer(value: String): String {
        var s = value.trim()
        if (s.isEmpty()) throw IllegalArgumentException("Enter your server URL")
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s.trimEnd('/')
    }

    private fun kdfLabel(pre: PreloginInfo): String =
        if (pre.isArgon2) {
            "Argon2id · ${pre.kdfIterations} iter · ${pre.kdfMemory ?: 64} MB · p${pre.kdfParallelism ?: 4}"
        } else {
            "PBKDF2-SHA256 · ${pre.kdfIterations} iterations"
        }

    private fun readable(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "Request failed"
        is TwoFactorRequiredException -> "Two-factor code needed"
        is IllegalArgumentException -> e.message ?: "Invalid input"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    companion object {
        const val DEFAULT_SERVER = "https://vault.bitwarden.com"
    }
}
