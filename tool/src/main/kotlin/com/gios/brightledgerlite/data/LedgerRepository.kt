package com.gios.brightledgerlite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gios.brightledgerlite.ledger.Ledger
import com.gios.brightledgerlite.ledger.SyncPolicy
import com.gios.brightledgerlite.simplefin.AccessUrl
import com.gios.brightledgerlite.simplefin.SimpleFinClient
import com.thelightphone.sdk.SealedLightContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Everything the tool stores, and the only code that talks to SimpleFIN.
 *
 * The sealed Access URL and a few small settings live in the SDK's DataStore; the cached
 * ledger lives in one JSON file in the tool's private files directory. Screens read the
 * cache and only the cache: a sync writes into it in the background, so a failed refresh
 * changes nothing on screen but the status line.
 *
 * A process-wide singleton, shared by the screens and the background job. It keeps only
 * process-lived handles (DataStore and the files directory), never a screen's context.
 */
class LedgerRepository private constructor(
    private val dataStore: DataStore<Preferences>,
    filesDir: File,
    private val client: SimpleFinClient = SimpleFinClient(),
) {

    /** Why a sync did or did not happen. */
    sealed interface Outcome {
        data class Ok(val warnings: List<String>) : Outcome

        /** Too soon since the last attempt; the cache is fresh enough. */
        data object Skipped : Outcome

        data object NotConnected : Outcome

        /** 403: access revoked on the server, or the stored URL is no longer valid. */
        data object NeedsReconnect : Outcome

        /** 402: the server's subscription lapsed. Paid to the server, not to this tool. */
        data object PaymentRequired : Outcome

        data class Failed(val message: String) : Outcome
    }

    enum class Trigger { Open, Manual, Background, FirstSync }

    sealed interface ClaimOutcome {
        data class Connected(val host: String) : ClaimOutcome
        data class Refused(val message: String) : ClaimOutcome
    }

    private val file = File(filesDir, LEDGER_FILE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private val _ledger = MutableStateFlow(Ledger())
    val ledger: StateFlow<Ledger> = _ledger.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private var loaded = false

    // ---- settings ----

    suspend fun isConnected(): Boolean = dataStore.data.first()[KEY_ACCESS] != null

    /** Host only, for display: "bridge.simplefin.org". */
    suspend fun host(): String? = dataStore.data.first()[KEY_HOST]

    suspend fun lastError(): String? = dataStore.data.first()[KEY_LAST_ERROR]

    suspend fun backgroundSync(): Boolean = dataStore.data.first()[KEY_BACKGROUND] == true

    suspend fun setBackgroundSync(enabled: Boolean) {
        dataStore.edit { it[KEY_BACKGROUND] = enabled }
    }

    private suspend fun access(): AccessUrl? {
        val sealed = dataStore.data.first()[KEY_ACCESS] ?: return null
        return Credential.open(sealed)?.let(AccessUrl::parse)
    }

    // ---- cache ----

    /** Reads the cached ledger from disk once per process. */
    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (loaded) return@withLock
            loaded = true
            if (file.exists()) {
                runCatching { json.decodeFromString(Ledger.serializer(), file.readText()) }
                    .onSuccess { _ledger.value = it }
            }
        }
    }

    private fun save(ledger: Ledger) {
        val tmp = File(file.parentFile, "$LEDGER_FILE.tmp")
        tmp.writeText(json.encodeToString(Ledger.serializer(), ledger))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        _ledger.value = ledger
    }

    // ---- claiming ----

    /**
     * Trades a setup token for an Access URL, seals it, and pulls the first year of history.
     *
     * The token is spent by the claim whether or not this phone gets the answer, which is
     * why a 403 here is reported as "already claimed" rather than as a generic error.
     */
    suspend fun claim(setupToken: String): ClaimOutcome = withContext(Dispatchers.IO) {
        when (val result = client.claim(setupToken)) {
            is SimpleFinClient.ClaimResult.AlreadyClaimed -> ClaimOutcome.Refused(
                "That token has already been used. A setup token works once. If you did not " +
                    "claim it yourself, revoke it on the Bridge and make a new one.",
            )
            is SimpleFinClient.ClaimResult.Failed -> ClaimOutcome.Refused(result.message)
            is SimpleFinClient.ClaimResult.Success -> {
                val access = result.accessUrl
                lock.withLock {
                    // The only call to rawForStorage(), and it goes straight into the seal.
                    val sealed = Credential.seal(access.rawForStorage())
                    dataStore.edit {
                        it[KEY_ACCESS] = sealed
                        it[KEY_HOST] = access.host
                        it.remove(KEY_LAST_ATTEMPT)
                        it.remove(KEY_LAST_ERROR)
                    }
                    file.delete()
                    _ledger.value = Ledger()
                    loaded = true
                }
                sync(Trigger.FirstSync)
                ClaimOutcome.Connected(access.host)
            }
        }
    }

    // ---- syncing ----

    /**
     * One `GET /accounts`, if the [trigger] is allowed one right now.
     *
     * Opening the tool and the background job only sync when the cache is older than
     * [SyncPolicy.AUTO_REFRESH_AFTER]; SYNC only refuses a second press inside a minute.
     */
    suspend fun sync(trigger: Trigger): Outcome = withContext(Dispatchers.IO) {
        load()
        if (!lock.tryLock()) return@withContext Outcome.Skipped
        try {
            val prefs = dataStore.data.first()
            if (prefs[KEY_ACCESS] == null) return@withContext Outcome.NotConnected
            val now = nowSeconds()
            val lastAttempt = prefs[KEY_LAST_ATTEMPT] ?: 0L
            val lastSuccess = _ledger.value.lastSuccess
            val allowed = when (trigger) {
                Trigger.Open, Trigger.Background -> SyncPolicy.shouldAutoRefresh(lastSuccess, lastAttempt, now)
                Trigger.Manual -> SyncPolicy.canManualRefresh(lastAttempt, now)
                Trigger.FirstSync -> true
            }
            if (!allowed) return@withContext Outcome.Skipped

            val access = access() ?: return@withContext Outcome.Failed(
                "The saved credential could not be read. Disconnect in Settings and connect again.",
            ).also { recordError(it.message) }

            _syncing.value = true
            dataStore.edit { it[KEY_LAST_ATTEMPT] = now }
            val windowStart = SyncPolicy.windowStart(lastSuccess, now)
            when (val result = client.fetchAccounts(access, startDate = windowStart)) {
                is SimpleFinClient.FetchResult.Forbidden -> {
                    recordError("Access was revoked. Disconnect and connect again with a new token.")
                    Outcome.NeedsReconnect
                }
                is SimpleFinClient.FetchResult.PaymentRequired -> {
                    recordError("The SimpleFIN server wants its subscription renewed.")
                    Outcome.PaymentRequired
                }
                is SimpleFinClient.FetchResult.Failed -> {
                    recordError(result.message)
                    Outcome.Failed(result.message)
                }
                is SimpleFinClient.FetchResult.Success -> {
                    val merged = Ledger.merge(
                        old = _ledger.value,
                        data = result.data,
                        windowStart = windowStart,
                        nowSeconds = nowSeconds(),
                        keepSinceSeconds = SyncPolicy.keepSince(now),
                    )
                    save(merged)
                    dataStore.edit { it.remove(KEY_LAST_ERROR) }
                    // Server text, shown as plain text and never parsed.
                    Outcome.Ok(result.data.errors.map { it.msg })
                }
            }
        } finally {
            _syncing.value = false
            lock.unlock()
        }
    }

    private suspend fun recordError(message: String) {
        dataStore.edit { it[KEY_LAST_ERROR] = message }
    }

    /**
     * Forgets the credential and every cached figure. This cannot revoke the grant on the
     * server; that is done on the Bridge, and the confirm screen says so.
     */
    suspend fun disconnect(lightContext: SealedLightContext) = withContext(Dispatchers.IO) {
        BackgroundSync.cancel(lightContext)
        lock.withLock {
            dataStore.edit {
                it.remove(KEY_ACCESS)
                it.remove(KEY_HOST)
                it.remove(KEY_LAST_ATTEMPT)
                it.remove(KEY_LAST_ERROR)
                it.remove(KEY_BACKGROUND)
            }
            Credential.destroy()
            file.delete()
            _ledger.value = Ledger()
        }
    }

    companion object {
        private val KEY_ACCESS = stringPreferencesKey("access_url_sealed")
        private val KEY_HOST = stringPreferencesKey("access_host")
        private val KEY_LAST_ATTEMPT = longPreferencesKey("last_attempt")
        private val KEY_LAST_ERROR = stringPreferencesKey("last_error")
        private val KEY_BACKGROUND = booleanPreferencesKey("background_sync")
        private const val LEDGER_FILE = "ledger.json"

        @Volatile
        private var instance: LedgerRepository? = null

        fun get(ctx: SealedLightContext): LedgerRepository =
            instance ?: synchronized(this) {
                instance ?: LedgerRepository(ctx.dataStore, ctx.filesDir).also { instance = it }
            }

        fun nowSeconds(): Long = System.currentTimeMillis() / 1000
    }
}
