package com.gios.brightledgerlite

import com.gios.brightledgerlite.simplefin.AccessUrl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import java.util.Base64

/**
 * The credential handling, which is the part of this app most worth testing and — because
 * it deliberately uses `java.util.Base64` rather than the Android one — the part that can
 * be tested without a device.
 */
class AccessUrlTest {

    /** The reusable demo token from the SimpleFIN v2.0.0 spec. */
    private val demoToken =
        "aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZy9zaW1wbGVmaW4vY2xhaW0vZGVtbw=="

    @Test
    fun `decodes the spec's demo token to its claim url`() {
        assertEquals(
            "https://bridge.simplefin.org/simplefin/claim/demo",
            AccessUrl.decodeSetupToken(demoToken),
        )
    }

    /**
     * A token copied out of a browser or read off a QR routinely arrives wrapped. Base64
     * decoding is whitespace-sensitive, so this is a real failure mode and not a nicety.
     */
    @Test
    fun `tolerates whitespace and newlines inside the token`() {
        val wrapped = demoToken.chunked(20).joinToString("\n")
        assertEquals(
            "https://bridge.simplefin.org/simplefin/claim/demo",
            AccessUrl.decodeSetupToken(wrapped),
        )
    }

    @Test
    fun `rejects tokens that are not base64 over an https url`() {
        assertNull(AccessUrl.decodeSetupToken(null))
        assertNull(AccessUrl.decodeSetupToken(""))
        assertNull(AccessUrl.decodeSetupToken("hello"))
        // Valid base64, but decodes to plain http.
        val insecure = Base64.getEncoder()
            .encodeToString("http://bridge.simplefin.org/claim/x".toByteArray())
        assertNull(AccessUrl.decodeSetupToken(insecure))
    }

    @Test
    fun `splits credentials out of the access url`() {
        val access = AccessUrl.parse("https://demo:demo@bridge.simplefin.org/simplefin")
        assertNotNull(access)
        assertEquals("https://bridge.simplefin.org/simplefin", access!!.base)
        assertEquals("bridge.simplefin.org", access.host)
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("demo:demo".toByteArray()),
            access.authorization(),
        )
    }

    /**
     * OkHttp parses userinfo and then does not send it, so a request built from the raw
     * string authenticates with nothing. The header is the only thing that works, and the
     * base must not carry the credentials into a log line.
     */
    @Test
    fun `the base url and toString never contain the credentials`() {
        val access = AccessUrl.parse("https://user123:sekrit@bridge.simplefin.org/simplefin")!!
        assertFalse(access.base.contains("sekrit"))
        assertFalse(access.toString().contains("sekrit"))
        assertFalse(access.accountsUrl().contains("sekrit"))
        // ...but the whole string is still recoverable for sealing into the vault.
        assertTrue(access.rawForStorage().contains("sekrit"))
    }

    @Test
    fun `cleartext is refused outright`() {
        assertNull(AccessUrl.parse("http://demo:demo@bridge.simplefin.org/simplefin"))
        assertNull(AccessUrl.parse("ftp://demo@bridge.simplefin.org/simplefin"))
        assertNull(AccessUrl.parse("not a url"))
        assertNull(AccessUrl.parse(null))
        assertNull(AccessUrl.parse(""))
    }

    @Test
    fun `a server needing no auth is still usable`() {
        val access = AccessUrl.parse("https://sfin.mybank.com/simplefin")
        assertNotNull(access)
        assertNull(access!!.authorization())
    }

    @Test
    fun `builds the accounts query the v2 spec documents`() {
        val access = AccessUrl.parse("https://demo:demo@bridge.simplefin.org/simplefin")!!
        val url = access.accountsUrl(startDate = 978_360_153L)
        assertTrue(url.startsWith("https://bridge.simplefin.org/simplefin/accounts?"))
        assertTrue(url.contains("version=2"))
        assertTrue(url.contains("start-date=978360153"))
        assertTrue(url.contains("pending=1"))
        assertFalse(url.contains("balances-only"))

        val cheap = access.accountsUrl(balancesOnly = true, pending = false)
        assertTrue(cheap.contains("balances-only=1"))
        assertFalse(cheap.contains("pending=1"))
    }

    @Test
    fun `a trailing slash does not produce a double slash`() {
        val access = AccessUrl.parse("https://demo:demo@bridge.simplefin.org/simplefin/")!!
        assertFalse(access.accountsUrl().contains("//accounts"))
    }
}
