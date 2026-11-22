package com.notandrej.wristpass.data

/** Result of `POST /identity/accounts/prelogin` — how the server derives the master key. */
data class PreloginInfo(
    val kdf: Int,
    val kdfIterations: Int,
    val kdfMemory: Int?,
    val kdfParallelism: Int?,
) {
    val isArgon2: Boolean get() = kdf == 1
}

/** The token set returned by the identity endpoint. */
data class SessionTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMs: Long,
    /** The protected (master-key-wrapped) user key, when the token response carries it. */
    val protectedUserKey: String?,
)

/** A login entry straight out of `/api/sync`, still encrypted. */
data class RawCipher(
    val id: String,
    val type: Int,
    val name: String?,
    val key: String?,
    val username: String?,
    val password: String?,
    val totp: String?,
    val uri: String?,
    val favorite: Boolean,
    val deleted: Boolean,
)

/** A decrypted login. Only ever lives in memory. */
data class VaultItem(
    val id: String,
    val name: String,
    val username: String?,
    val password: String?,
    val totpSeed: String?,
    val uri: String?,
    val favorite: Boolean,
) {
    val hasTotp: Boolean get() = !totpSeed.isNullOrBlank()
}

/** Signals that the account needs a second factor before the token can be issued. */
class TwoFactorRequiredException(val providers: List<String>, message: String) : Exception(message)

/** Any non-2xx response from the server, with a readable message. */
class ApiException(
    val statusCode: Int,
    override val message: String,
    val invalidCredentials: Boolean = false,
) : Exception(message)
