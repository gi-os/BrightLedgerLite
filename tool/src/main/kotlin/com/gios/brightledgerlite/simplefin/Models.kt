package com.gios.brightledgerlite.simplefin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The SimpleFIN v2.0.0 wire types, and the parser that turns a response body into them.
 *
 * BrightLedger parsed these with `org.json`. The Lite tool reads the same fields through
 * kotlinx.serialization's tree API instead, because it is on the SDK's allowlist and, unlike
 * the `org.json` stubs in `android.jar`, it behaves the same in a unit test as on the phone.
 * The rules did not change: every field comes from a server the user chose, so each one may
 * be absent, null, or the wrong type, and a parser that throws on a missing optional would
 * take the whole sync down for one bad row.
 *
 * A malformed *row* is dropped, a malformed *document* is an error.
 */

/** A structured error from `errlist`. See the spec's `prefix.[subcode]` scheme. */
data class SfError(
    val code: String,
    val msg: String,
    val connId: String? = null,
    val accountId: String? = null,
) {
    /**
     * `gen`, `con` or `act`. Unknown subcodes fall back to the bare prefix, which is what
     * the spec asks consumers to do rather than treating a new subcode as unrecognised.
     */
    val prefix: String get() = code.substringBefore('.', code)

    /** True for the errors that mean "reconnect this bank", not "try again later". */
    val needsReauth: Boolean get() = code == "con.auth" || code == "gen.auth"
}

/** One login at one institution. Two logins at the same bank are two Connections. */
data class SfConnection(
    val connId: String,
    val name: String,
    val orgId: String,
    val orgUrl: String? = null,
    val sfinUrl: String? = null,
)

data class SfTransaction(
    val id: String,
    val posted: Long,
    val amountCents: Long,
    val description: String,
    val transactedAt: Long? = null,
    val pending: Boolean = false,
) {
    /**
     * When it happened, falling back to when it posted.
     *
     * A pending transaction may carry `posted = 0`, which the spec permits, so ordering on
     * `posted` alone drops every pending row to the bottom of the list, the opposite of
     * where the thing you just bought belongs.
     */
    val effectiveAt: Long get() = transactedAt ?: posted.takeIf { it > 0L } ?: 0L
}

data class SfAccount(
    val id: String,
    val name: String,
    val connId: String?,
    val currency: String,
    val balanceCents: Long,
    val availableBalanceCents: Long?,
    val balanceDate: Long,
    val transactions: List<SfTransaction> = emptyList(),
)

data class SfAccountSet(
    val errors: List<SfError>,
    val connections: List<SfConnection>,
    val accounts: List<SfAccount>,
)

object SfParser {

    private val json = Json { isLenient = false }

    /** Throws only when the body is not a JSON object at all. */
    fun parseAccountSet(body: String): SfAccountSet {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("Not a JSON object")
        return SfAccountSet(
            errors = parseErrors(root),
            connections = root.array("connections").mapObjects(::parseConnection),
            accounts = root.array("accounts").mapObjects(::parseAccount),
        )
    }

    /**
     * `errlist` in v2, with a fallback to the v1 `errors` array of bare strings.
     *
     * v2.0.0 deprecated `errors` but did not remove it, and a server may report version 2
     * while still populating the old field, so both are read and merged.
     */
    private fun parseErrors(root: JsonObject): List<SfError> {
        val structured = root.array("errlist").mapObjects { item ->
            val code = item.string("code") ?: return@mapObjects null
            SfError(
                code = code,
                msg = item.string("msg") ?: code,
                connId = item.string("conn_id"),
                accountId = item.string("account_id"),
            )
        }
        val legacy = root.array("errors")?.mapNotNull { element ->
            val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text.isNullOrBlank()) null else SfError(code = "gen.", msg = text)
        }.orEmpty()
        return structured + legacy
    }

    private fun parseConnection(item: JsonObject): SfConnection? {
        val connId = item.string("conn_id") ?: return null
        return SfConnection(
            connId = connId,
            name = item.string("name") ?: connId,
            orgId = item.string("org_id").orEmpty(),
            orgUrl = item.string("org_url"),
            sfinUrl = item.string("sfin_url"),
        )
    }

    private fun parseAccount(item: JsonObject): SfAccount? {
        val id = item.string("id") ?: return null
        // A balance that will not parse is not an account worth showing: every screen leads
        // with one, and a zero placeholder would read as a real empty account.
        val balance = Money.parseCents(item.string("balance")) ?: return null
        return SfAccount(
            id = id,
            name = item.string("name") ?: id,
            connId = item.string("conn_id"),
            currency = item.string("currency") ?: "USD",
            balanceCents = balance,
            availableBalanceCents = Money.parseCents(item.string("available-balance")),
            balanceDate = item.long("balance-date") ?: 0L,
            transactions = item.array("transactions").mapObjects(::parseTransaction),
        )
    }

    private fun parseTransaction(item: JsonObject): SfTransaction? {
        val id = item.string("id") ?: return null
        val amount = Money.parseCents(item.string("amount")) ?: return null
        return SfTransaction(
            id = id,
            posted = item.long("posted") ?: 0L,
            amountCents = amount,
            description = item.string("description").orEmpty(),
            transactedAt = item.long("transacted_at")?.takeIf { it > 0L },
            pending = item.bool("pending") ?: false,
        )
    }

    /**
     * A string field, or null when absent, JSON null, or blank. Numbers are accepted and
     * read as their text, since an id or an amount sent unquoted is still that id or amount.
     * A JSON null must never come back as the text "null" and end up on screen as an
     * account called null.
     */
    private fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** A whole number, sent as a number or as a numeric string. */
    private fun JsonObject.long(key: String): Long? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.longOrNull ?: value.content.trim().toLongOrNull()
            ?: value.content.trim().toDoubleOrNull()?.toLong()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.booleanOrNull ?: when (value.content.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private inline fun <T> JsonArray?.mapObjects(transform: (JsonObject) -> T?): List<T> {
        val array = this ?: return emptyList()
        val out = ArrayList<T>(array.size)
        for (element: JsonElement in array) {
            val item = element as? JsonObject ?: continue
            transform(item)?.let(out::add)
        }
        return out
    }
}
