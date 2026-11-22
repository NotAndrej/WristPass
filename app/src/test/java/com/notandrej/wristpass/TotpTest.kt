package com.notandrej.wristpass

import com.notandrej.wristpass.crypto.Totp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RFC 6238 Appendix B test vectors (the authoritative ones, not self-generated)
 * plus the otpauth:// parsing used for Bitwarden's richer TOTP seeds.
 */
class TotpTest {

    private fun params(secretAscii: String, digits: Int, algorithm: String) = Totp.Params(
        secret = secretAscii.toByteArray(),
        digits = digits,
        period = 30,
        algorithm = algorithm,
        issuer = null,
        account = null,
    )

    @Test
    fun `rfc6238 sha1 vectors`() {
        // Secret is the ASCII "12345678901234567890".
        val p = params("12345678901234567890", 8, "SHA1")
        assertEquals("94287082", Totp.code(p, 59))
        assertEquals("07081804", Totp.code(p, 1111111109))
        assertEquals("14050471", Totp.code(p, 1111111111))
        assertEquals("89005924", Totp.code(p, 1234567890))
        assertEquals("69279037", Totp.code(p, 2000000000))
        assertEquals("65353130", Totp.code(p, 20000000000))
    }

    @Test
    fun `rfc6238 sha256 and sha512 vectors`() {
        val sha256 = params("12345678901234567890123456789012", 8, "SHA256")
        assertEquals("46119246", Totp.code(sha256, 59))
        val sha512 = params(
            "1234567890123456789012345678901234567890123456789012345678901234",
            8,
            "SHA512",
        )
        assertEquals("90693936", Totp.code(sha512, 59))
    }

    @Test
    fun `six digits truncate the eight digit code`() {
        val p = params("12345678901234567890", 6, "SHA1")
        assertEquals("287082", Totp.code(p, 59))
    }

    @Test
    fun `base32 decoding matches the rfc secret`() {
        val decoded = Totp.base32Decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertEquals("12345678901234567890", decoded.decodeToString())
        // Lower case, spaces and padding are tolerated.
        assertEquals(
            "12345678901234567890",
            Totp.base32Decode("gezd gnbv gy3t qojq gezd gnbv gy3t qojq==").decodeToString(),
        )
    }

    @Test
    fun `otpauth uri overrides digits and algorithm`() {
        val parsed = Totp.parse(
            "otpauth://totp/Router:admin?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" +
                "&digits=8&period=30&algorithm=SHA256",
        )
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals(8, parsed.digits)
        assertEquals("SHA256", parsed.algorithm)
        assertEquals("Router", parsed.issuer)
        assertEquals("admin", parsed.account)
        assertEquals("12345678901234567890", parsed.secret.decodeToString())
        // 8 digits + SHA256 over this seed, at a fixed instant (cross-checked with
        // an independent implementation).
        assertEquals("50869966", Totp.code(parsed, 1700000000))
    }

    @Test
    fun `a bare base32 seed uses the defaults`() {
        val parsed = Totp.parse("JBSWY3DPEHPK3PXP")
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals(6, parsed.digits)
        assertEquals(30, parsed.period)
        assertEquals("SHA1", parsed.algorithm)
    }

    @Test
    fun `junk seeds are rejected rather than guessed at`() {
        assertNull(Totp.parse(""))
        assertNull(Totp.parse("!!!not base32!!!"))
    }

    @Test
    fun `countdown is whole seconds to the next step`() {
        val p = Totp.parse("JBSWY3DPEHPK3PXP")
        requireNotNull(p)
        assertEquals(30, Totp.secondsRemaining(p, 0))
        assertEquals(20, Totp.secondsRemaining(p, 100))
        assertEquals(1, Totp.secondsRemaining(p, 29))
    }
}
