package com.notandrej.wristpass

import com.notandrej.wristpass.crypto.Crypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Runs the app's own Kotlin crypto against artifacts captured from a live
 * Vaultwarden 1.37.5 instance (an account plus one GitHub login whose password
 * and TOTP seed are known). If any byte-level step of the Bitwarden scheme were
 * wrong — PBKDF2, the HKDF "stretch", the AES-CBC-HMAC unwrap, or the per-item
 * key layer — one of these assertions would fail.
 */
class CryptoVectorTest {

    private val masterKey: ByteArray get() = Crypto.b64Decode(MASTER_KEY_B64)

    @Test
    fun `pbkdf2 derives the account master key`() {
        val derived = Crypto.pbkdf2Sha256(
            MASTER_PASSWORD.toByteArray(),
            EMAIL.lowercase().toByteArray(),
            KDF_ITERATIONS.toInt(),
            32,
        )
        assertEquals(MASTER_KEY_B64, Crypto.b64Encode(derived))
    }

    @Test
    fun `master password hash matches the server`() {
        val hash = Crypto.pbkdf2Sha256(masterKey, MASTER_PASSWORD.toByteArray(), 1, 32)
        assertEquals(MASTER_PASSWORD_HASH, Crypto.b64Encode(hash))
    }

    @Test
    fun `stretched master key unwraps the user key`() {
        val (enc, mac) = Crypto.stretchKey(masterKey)
        val userKey = Crypto.decryptCipherString(WRAPPED_USER_KEY, enc, mac)
        assertEquals(USER_KEY_B64, Crypto.b64Encode(userKey))
    }

    @Test
    fun `per-item key and all login fields decrypt`() {
        val userKey = Crypto.b64Decode(USER_KEY_B64)
        // 64-byte keys are already enc||mac; a 32-byte user key is stretched.
        val (uEnc, uMac) = Crypto.toEncMac(userKey)

        val itemKey = Crypto.decryptCipherString(ITEM_WRAPPED_KEY, uEnc, uMac)
        val (enc, mac) = Crypto.toEncMac(itemKey)

        assertEquals(P_NAME, Crypto.decryptCipherString(C_NAME, enc, mac).decodeToString())
        assertEquals(P_USERNAME, Crypto.decryptCipherString(C_USERNAME, enc, mac).decodeToString())
        assertEquals(P_PASSWORD, Crypto.decryptCipherString(C_PASSWORD, enc, mac).decodeToString())
        assertEquals(P_TOTP, Crypto.decryptCipherString(C_TOTP, enc, mac).decodeToString())
        assertEquals(P_URI, Crypto.decryptCipherString(C_URI, enc, mac).decodeToString())
    }

    @Test
    fun `a tampered ciphertext is rejected before decryption`() {
        val (enc, mac) = Crypto.stretchKey(masterKey)
        // Flip one base64 character of the ciphertext so the HMAC no longer matches.
        val parts = WRAPPED_USER_KEY.substringAfter('.').split('|').toMutableList()
        val ct = parts[1]
        parts[1] = (if (ct[4] == 'A') 'B' else 'A') + ct.substring(1)
        val tampered = "2." + parts.joinToString("|")
        assertThrows(SecurityException::class.java) {
            Crypto.decryptCipherString(tampered, enc, mac)
        }
    }

    @Test
    fun `hkdf expand is RFC 5869 shaped`() {
        // Deterministic and independent of AES: same input -> same bytes.
        val prk = ByteArray(32) { it.toByte() }
        assertEquals(
            Crypto.b64Encode(Crypto.hkdfExpand(prk, "enc", 32)),
            Crypto.b64Encode(Crypto.hkdfExpand(prk, "enc", 32)),
        )
        assertEquals(32, Crypto.hkdfExpand(prk, "enc", 32).size)
        assertEquals(64, Crypto.hkdfExpand(prk, "mac", 64).size)
    }

    private companion object {
    private const val EMAIL = "watch@wristpass.test"
    private const val MASTER_PASSWORD = "WristPass-Test-2026!"
    private const val KDF_ITERATIONS = "600000"
    private const val MASTER_KEY_B64 = "xfaTWcQNHWWVOpbzDFrrkNE+d1FKand4Z50pn7Lp9bk="
    private const val MASTER_PASSWORD_HASH = "ej2QsXTsFs3xLlv6m0tA41JV6z+TK3iXxEs94xHiRQg="
    private const val WRAPPED_USER_KEY = "2.dKofMpzJ71bAqXvHuap8rg==|svWFX7kYSLpnUib2a+eOgKP2P+Pq86gTLQGMslEda8YzmKpyzNOvctsQ+SeE0xV5|5KjGAXDg5DnIdWRtiJIs31Z3aC1c89NHIo4SF6F2/cM="
    private const val USER_KEY_B64 = "UjXP6MtwGMsIQ5NSzZ7Acz3NHLXOh8eCAyP0TX54p2Y="
    private const val ITEM_WRAPPED_KEY = "2.Lkkg4sOFdIQ+/LEKuTxoew==|8DpexR23HMm1CYBdG/atZu3FswGFFrQ/IsNFCvfGE+yfFQiIPXe7URsDi8MHN5B6|8j/NdaweKeGGN1EGhrVKnIEDXFsCfviHBgJ+VRv4stM="
    private const val C_NAME = "2.RyIveDKu9+akYm2xlKB7ow==|V0j6Ihsl8rKsckwA1Lk/8A==|u92wehcKCH5bbW5sLZ8ql3llqbWagTfwPmNipR4C8JA="
    private const val C_USERNAME = "2.ur5zKTyJdiosc7QzoujX/A==|yOpJYzA1j9qsjlvKad32sg==|h7FcYyD8kJH2mpYzIrVFWWMyvagTvWvWrUtiMhaR8wE="
    private const val C_PASSWORD = "2.0qhdy8HhwBsnfeqbkt2+ww==|ei29t470lN2fzyJCTxss7weG01K0LyKuzVvFA4XN1Mo=|9E3zlCalqSryEDojpyFxI2tbMHgnYnfXh02Lo1JarVw="
    private const val C_TOTP = "2.SVFSOoOf3icNM9ee7ikFWQ==|vB8rotFp2pN+Jy9r9upEmfPPF94ST+L3esjh6EydZPU=|GQRAPcIkq21dr33/zFCB+WttSJM2BMO2VlyB4llzQ7U="
    private const val C_URI = "2.tWf2f1uCPsj/MXhSTXpXvw==|3rSeEBeWR1NRUx//RRXFEWqeh47x2ZOjbnhyID37UNs=|G5oaElCk+HLwcQLy1k2q1oo9p3XehtyKkjbWRcxp7oM="
    private const val P_NAME = "GitHub"
    private const val P_USERNAME = "notandrej"
    private const val P_PASSWORD = "hunter2-very-secret"
    private const val P_TOTP = "JBSWY3DPEHPK3PXP"
    private const val P_URI = "https://github.com/login"
    }
}
