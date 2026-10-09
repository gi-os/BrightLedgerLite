package com.gios.brightledgerlite.simplefin

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The entire SimpleFIN integration: claim once, fetch forever.
 *
 * There is no backend behind this app and no API key in it. The user creates a token on the
 * Bridge, the app trades that token for an Access URL exactly once, and from then on every
 * request is a plain authenticated GET straight from the phone to the server the user chose.
 * Nothing about this class talks to anything owned by this tool, because nothing is.
 */
class SimpleFinClient(
    private val http: OkHttpClient = defaultClient(),
) {

    sealed interface ClaimResult {
        data class Success(val accessUrl: AccessUrl) : ClaimResult

        /**
         * 403 from the claim endpoint. The spec is blunt about what this means: the token
         * either never existed or **someone else already claimed it**, and the user has to be
         * told the second possibility rather than shown a generic failure, because the
         * remedy is to go revoke it.
         */
        data object AlreadyClaimed : ClaimResult

        data class Failed(val message: String) : ClaimResult
    }

    sealed interface FetchResult {
        data class Success(val data: SfAccountSet) : FetchResult

        /** 403. Access revoked from the Bridge, or the stored URL is no longer valid. */
        data object Forbidden : FetchResult

        /** 402. The Bridge subscription lapsed; the user pays the Bridge, not this app. */
        data object PaymentRequired : FetchResult

        data class Failed(val message: String) : FetchResult
    }

    /** Step 3 of the quickstart. One shot — the token is spent whether or not this succeeds. */
    fun claim(setupToken: String): ClaimResult {
        val claimUrl = AccessUrl.decodeSetupToken(setupToken)
            ?: return ClaimResult.Failed("That does not look like a SimpleFIN token.")

        val request = Request.Builder()
            .url(claimUrl)
            .post(ByteArray(0).toRequestBody(null, 0, 0))
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 403 -> ClaimResult.AlreadyClaimed
                    !response.isSuccessful -> ClaimResult.Failed(
                        "The server refused the token (HTTP " + response.code + ")."
                    )
                    else -> {
                        val body = response.body?.string()?.trim()
                        val parsed = AccessUrl.parse(body)
                        if (parsed == null) {
                            ClaimResult.Failed("The server returned something that is not an https access URL.")
                        } else {
                            ClaimResult.Success(parsed)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            ClaimResult.Failed(networkMessage(e))
        }
    }

    /**
     * Step 4, and every sync after it.
     *
     * [startDate] is what keeps this cheap. The server will happily return years of history
     * on every call; asking only for what has happened since the last successful sync turns a
     * routine refresh into a few kilobytes, which matters on a phone that is often on cellular
     * and always on a small battery.
     */
    fun fetchAccounts(
        access: AccessUrl,
        startDate: Long? = null,
        balancesOnly: Boolean = false,
    ): FetchResult {
        val builder = Request.Builder()
            .url(access.accountsUrl(startDate = startDate, balancesOnly = balancesOnly))
            .get()
        access.authorization()?.let { builder.header("Authorization", it) }

        return try {
            http.newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    403 -> FetchResult.Forbidden
                    402 -> FetchResult.PaymentRequired
                    in 200..299 -> {
                        val body = response.body?.string()
                        if (body.isNullOrBlank()) {
                            FetchResult.Failed("The server returned an empty response.")
                        } else {
                            runCatching { SfParser.parseAccountSet(body) }
                                .fold(
                                    onSuccess = { FetchResult.Success(it) },
                                    onFailure = {
                                        FetchResult.Failed("The server returned malformed data.")
                                    },
                                )
                        }
                    }
                    else -> FetchResult.Failed("The server returned HTTP " + response.code + ".")
                }
            }
        } catch (e: IOException) {
            FetchResult.Failed(networkMessage(e))
        }
    }

    /**
     * Exceptions are summarised rather than surfaced.
     *
     * An IOException's message can contain the request URL, and the request URL is one
     * mistake away from containing the credentials. Nothing derived from the exception text
     * reaches the screen.
     */
    private fun networkMessage(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "No connection."
        is java.net.SocketTimeoutException -> "The server did not respond in time."
        is javax.net.ssl.SSLException -> "The secure connection failed."
        else -> "Could not reach the server."
    }

    companion object {
        /**
         * Generous timeouts on purpose. The Bridge refreshes a bank connection live while the
         * request is open, so a first sync after a few days can genuinely take half a minute,
         * and a client that gives up at ten seconds turns a slow bank into a broken app.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
