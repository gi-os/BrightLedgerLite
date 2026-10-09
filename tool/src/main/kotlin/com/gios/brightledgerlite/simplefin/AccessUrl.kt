package com.gios.brightledgerlite.simplefin

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * A claimed SimpleFIN Access URL, split into the part you can log and the part you cannot.
 *
 * The Access URL *is* the credential. The spec hands back a single string of the form
 * `https://user:pass@bridge.simplefin.org/simplefin`, which means the secret is embedded in
 * something that otherwise looks like a URL and will end up in a log line, a crash report or
 * a Retrofit `toString` the moment anyone treats it as one. So it is torn apart on arrival:
 * [base] is safe to show and to log, [authHeader] never leaves this object except into a
 * request header.
 *
 * Splitting it is also load-bearing rather than merely tidy. OkHttp parses userinfo in a URL
 * and then does nothing with it — it does not send Basic auth for embedded credentials — so a
 * request built from the raw string authenticates with nothing and comes back 403. The
 * credentials have to become a header by hand.
 */
class AccessUrl private constructor(
    /** Scheme, host and path, with the credentials removed. Safe to display. */
    val base: String,
    private val authHeader: String?,
    private val raw: String,
) {

    /**
     * The whole string, credentials and all, for sealing into the credential store.
     *
     * Named so that a call site reads as a decision rather than as a getter. This is the only
     * way back to the complete URL, it is called from exactly one place, and what it returns
     * must go straight into [com.gios.brightledgerlite.data.Credential.seal] — never into a log,
     * a bundle, a crash report, or an intent.
     */
    fun rawForStorage(): String = raw

    /** Host only, for the settings screen: "bridge.simplefin.org". */
    val host: String get() = runCatching { URI(base).host }.getOrNull() ?: base

    /** The value for an `Authorization` header, or null for a server wanting no auth. */
    fun authorization(): String? = authHeader

    /** `<base>/accounts?...`, with the query built from whatever is non-null. */
    fun accountsUrl(
        startDate: Long? = null,
        endDate: Long? = null,
        pending: Boolean = true,
        balancesOnly: Boolean = false,
    ): String {
        val params = buildList {
            add("version=2")
            if (startDate != null) add("start-date=$startDate")
            if (endDate != null) add("end-date=$endDate")
            if (pending) add("pending=1")
            if (balancesOnly) add("balances-only=1")
        }
        return base.trimEnd('/') + "/accounts?" + params.joinToString("&")
    }

    companion object {

        /**
         * Null when the string is not a usable Access URL.
         *
         * HTTPS is required, not preferred: the credentials ride in a header on every single
         * request, so one cleartext call leaks read access to a bank account. This is the
         * spec's own checklist item and the app enforces it rather than trusting the server
         * to have returned the right scheme.
         */
        fun parse(raw: String?): AccessUrl? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host ?: return null

            // java.util.Base64 rather than android.util.Base64, so every line of this class
            // runs in a plain JVM test. The credential handling is the part most worth
            // testing and the part least worth needing an emulator to test.
            val userInfo = uri.userInfo
            val header = userInfo?.let {
                val bytes = it.toByteArray(StandardCharsets.UTF_8)
                "Basic " + Base64.getEncoder().encodeToString(bytes)
            }

            val port = if (uri.port > 0) ":" + uri.port else ""
            val path = uri.rawPath.orEmpty().trimEnd('/')
            return AccessUrl(
                base = "https://" + host + port + path,
                authHeader = header,
                raw = text,
            )
        }

        /**
         * A SimpleFIN Token is Base64 over the one-time claim URL. Decoding is the whole of
         * step two, and it is separate from claiming so a mistyped token fails immediately
         * with "that is not a token" instead of burning a network round trip.
         *
         * Whitespace is stripped first: the token is long enough that pasting it out of a
         * browser or reading it off a QR routinely carries a newline in the middle.
         */
        fun decodeSetupToken(token: String?): String? {
            val cleaned = token?.filterNot { it.isWhitespace() }.orEmpty()
            if (cleaned.isEmpty()) return null
            val decoded = runCatching {
                String(Base64.getDecoder().decode(cleaned), StandardCharsets.UTF_8)
            }.getOrNull()?.trim() ?: return null
            if (!decoded.startsWith("https://")) return null
            // A claim URL with no path is a redirect waiting to happen, not a claim endpoint.
            if (runCatching { URI(decoded).host }.getOrNull().isNullOrBlank()) return null
            return decoded
        }
    }

    /** Never the credentials, in any log, ever. */
    override fun toString(): String = "AccessUrl(" + host + ")"
}
