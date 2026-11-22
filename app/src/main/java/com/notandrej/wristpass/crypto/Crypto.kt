package com.notandrej.wristpass.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Bitwarden cryptography WristPass needs, matched to the official clients
 * and to Vaultwarden:
 *
 *   masterKey           = PBKDF2-SHA256(masterPassword, email.lowercase(), iters)   [kdf 0]
 *                       = Argon2id(masterPassword, email.lowercase(), ...)          [kdf 1]
 *   masterPasswordHash  = base64( PBKDF2-SHA256(masterKey, masterPassword, 1) )
 *   <stretched>         = HKDF-Expand(key, "enc", 32) || HKDF-Expand(key, "mac", 32)
 *
 * The stretched master key unwraps `profile.key` (a "2." AES-CBC-HMAC blob) into
 * the 32-byte *user key*; the stretched user key then unwraps each item's
 * optional per-item key, and finally the item's encrypted fields.
 *
 * Every byte-level step here was checked against a live Vaultwarden instance.
 */
object Crypto {

    const val ENC_TYPE_AESCBC256_B64 = 0
    const val ENC_TYPE_AESCBC256_HMACSHA256_B64 = 2

    fun b64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun b64Decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    /** PBKDF2-HMAC-SHA256. BouncyCastle is used rather than SecretKeyFactory so the
     *  password is treated as raw UTF-8 bytes, not as (platform-specific) chars. */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLenBytes: Int = 32): ByteArray {
        val gen = PKCS5S2ParametersGenerator(SHA256Digest())
        gen.init(password, salt, iterations)
        return (gen.generateDerivedParameters(dkLenBytes * 8) as KeyParameter).key
    }

    /** Argon2id (RFC 9106), as used by Bitwarden when kdf = 1. */
    fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKib: Int,
        parallelism: Int,
        dkLenBytes: Int = 32,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKib)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(dkLenBytes)
        generator.generateBytes(password, out)
        return out
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 HKDF-Expand with SHA-256 (the layer Bitwarden's key "stretching" uses). */
    fun hkdfExpand(prk: ByteArray, info: String, outLenBytes: Int = 32): ByteArray {
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val blocks = (outLenBytes + 31) / 32
        val okm = ByteArray(blocks * 32)
        var previous = ByteArray(0)
        for (counter in 1..blocks) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(infoBytes)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            System.arraycopy(previous, 0, okm, (counter - 1) * 32, 32)
        }
        return okm.copyOf(outLenBytes)
    }

    /** Splits a 32-byte key into the (enc, mac) pair Bitwarden uses, via HKDF-Expand. */
    fun stretchKey(key32: ByteArray): Pair<ByteArray, ByteArray> =
        hkdfExpand(key32, "enc", 32) to hkdfExpand(key32, "mac", 32)

    /** A Bitwarden symmetric key: 32 bytes get stretched, 64 bytes are already enc||mac. */
    fun toEncMac(key: ByteArray): Pair<ByteArray, ByteArray> = when (key.size) {
        32 -> stretchKey(key)
        64 -> key.copyOfRange(0, 32) to key.copyOfRange(32, 64)
        else -> throw IllegalArgumentException("Unsupported key length ${key.size}")
    }

    /**
     * Decrypts a Bitwarden protected string. Only encryption types 0 and 2 are
     * used for vault data; anything else is reported rather than guessed at.
     * For type 2 the HMAC is checked before any plaintext is produced.
     */
    fun decryptCipherString(cipherString: String, encKey: ByteArray, macKey: ByteArray?): ByteArray {
        val dot = cipherString.indexOf('.')
        require(dot > 0) { "Malformed cipher string" }
        val type = cipherString.substring(0, dot).toIntOrNull()
            ?: throw IllegalArgumentException("Malformed cipher type")

        val body = cipherString.substring(dot + 1)
        val parts = body.split('|')
        when (type) {
            ENC_TYPE_AESCBC256_HMACSHA256_B64 -> {
                if (parts.size < 3) throw IllegalArgumentException("Malformed type-2 cipher")
                val iv = b64Decode(parts[0])
                val ct = b64Decode(parts[1])
                val tag = b64Decode(parts[2])
                val mac = macKey ?: throw IllegalArgumentException("Missing MAC key for type-2 cipher")
                val expected = hmacSha256(mac, iv + ct)
                if (!MessageDigest.isEqual(expected, tag)) {
                    throw SecurityException("MAC verification failed")
                }
                return aesCbcDecrypt(encKey, iv, ct)
            }
            ENC_TYPE_AESCBC256_B64 -> {
                if (parts.size < 2) throw IllegalArgumentException("Malformed type-0 cipher")
                val iv = b64Decode(parts[0])
                val ct = b64Decode(parts[1])
                return aesCbcDecrypt(encKey, iv, ct)
            }
            else -> throw IllegalArgumentException("Unsupported cipher type $type")
        }
    }

    private fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }
}
