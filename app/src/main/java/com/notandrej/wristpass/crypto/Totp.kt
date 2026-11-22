package com.notandrej.wristpass.crypto

import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP (RFC 6238) for the seeds Bitwarden stores in a login's `totp` field.
 * That field is either a raw Base32 secret or a full `otpauth://` URI, so both
 * are accepted, along with the per-item digits / period / algorithm overrides.
 */
object Totp {

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    data class Params(
        val secret: ByteArray,
        val digits: Int,
        val period: Int,
        val algorithm: String,
        val issuer: String?,
        val account: String?,
    )

    /** Base32 decode (RFC 4648), tolerant of spaces, dashes, lowercase and padding. */
    fun base32Decode(input: String): ByteArray {
        val clean = input.trim()
            .replace(" ", "")
            .replace("-", "")
            .trimEnd('=')
            .uppercase()
        val out = ArrayList<Byte>(clean.length * 5 / 8 + 1)
        var buffer = 0L
        var bits = 0
        for (ch in clean) {
            val value = BASE32_ALPHABET.indexOf(ch)
            if (value < 0) continue
            buffer = (buffer shl 5) or value.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    /**
     * Parses either an `otpauth://totp/...` URI or a bare Base32 secret.
     * The URI is parsed by hand rather than with `android.net.Uri` so this stays
     * pure-JVM and can be exercised by unit tests.
     */
    fun parse(raw: String): Params? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("otpauth://", ignoreCase = true)) {
            val query = text.substringAfter('?', "")
            val params = parseQuery(query)
            val secret = params["secret"] ?: return null
            val bytes = base32Decode(secret)
            if (bytes.isEmpty()) return null

            // otpauth://totp/<issuer:account>?...  (label may be omitted)
            val path = text.substringAfter("://", "").substringBefore('?')
            val label = path.substringAfter('/', "").ifBlank { null }
            val decodedLabel = label?.let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
            }
            val labelIssuer = decodedLabel?.takeIf { it.contains(':') }?.substringBefore(':')
            val account = decodedLabel?.let { if (it.contains(':')) it.substringAfter(':') else it }
            val issuer = params["issuer"] ?: labelIssuer

            return Params(
                secret = bytes,
                digits = params["digits"]?.toIntOrNull()?.coerceIn(6, 10) ?: 6,
                period = params["period"]?.toIntOrNull()?.takeIf { it > 0 } ?: 30,
                algorithm = params["algorithm"]?.uppercase() ?: "SHA1",
                issuer = issuer,
                account = account,
            )
        }
        val bytes = base32Decode(text)
        if (bytes.isEmpty() || !looksLikeBase32(text)) return null
        return Params(bytes, 6, 30, "SHA1", null, null)
    }

    /** True when every non-separator character belongs to the Base32 alphabet. */
    private fun looksLikeBase32(text: String): Boolean =
        text.replace(" ", "")
            .replace("-", "")
            .trimEnd('=')
            .all { it.uppercaseChar() in BASE32_ALPHABET }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else {
                    val key = part.substring(0, idx).lowercase()
                    val value = runCatching { URLDecoder.decode(part.substring(idx + 1), "UTF-8") }
                        .getOrDefault(part.substring(idx + 1))
                    key to value
                }
            }
            .toMap()

    fun code(params: Params, atEpochSeconds: Long): String {
        val counter = atEpochSeconds / params.period
        val message = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            message[i] = (c and 0xFF).toByte()
            c = c shr 8
        }
        val macAlgorithm = when (params.algorithm) {
            "SHA256" -> "HmacSHA256"
            "SHA512" -> "HmacSHA512"
            else -> "HmacSHA1"
        }
        val mac = Mac.getInstance(macAlgorithm)
        mac.init(SecretKeySpec(params.secret, macAlgorithm))
        val hash = mac.doFinal(message)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val truncated = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        var modulus = 1
        repeat(params.digits) { modulus *= 10 }
        return (truncated % modulus).toString().padStart(params.digits, '0')
    }

    /** Whole seconds until the current code rolls over. */
    fun secondsRemaining(params: Params, atEpochSeconds: Long): Int =
        (params.period - (atEpochSeconds % params.period)).toInt()
}
