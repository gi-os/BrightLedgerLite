package com.gios.brightledgerlite.ledger

/**
 * How far back each sync asks, how much history is kept, and how often the tool is allowed
 * to ask at all. All in epoch seconds, all pure, so the rules can be tested.
 */
object SyncPolicy {

    private const val DAY = 86_400L

    /** How much history a first sync pulls. Enough for the repeats detector to see a year. */
    const val INITIAL_DAYS = 400L

    /**
     * How far back a later sync re-reads.
     *
     * Not "since the last sync". A bank can post a transaction dated several days before it
     * appears, and a pending charge can change amount when it settles, so a window that
     * starts at the last sync misses both. Fourteen days of overlap costs a few kilobytes
     * and makes the sync self-healing: every row in the window is fetched again and replaced.
     * Same value as BrightLedger.
     */
    const val OVERLAP_DAYS = 14L

    /** History older than this is dropped from the cache. */
    const val KEEP_DAYS = INITIAL_DAYS

    /** How far back an account's transaction list goes on screen. */
    const val SHOW_DAYS = 60L

    /**
     * Opening the tool refreshes only when the data is at least this old.
     *
     * BrightLedger refreshed on every foregrounding. The SimpleFIN Bridge caps how many
     * requests one access URL may make per day and only pulls from the bank about once a
     * day anyway, so a tool that refreshes every time it is glanced at burns that quota for
     * nothing. Thirty minutes keeps the numbers current without that.
     */
    const val AUTO_REFRESH_AFTER = 30L * 60

    /** A failed attempt is not retried automatically sooner than this. */
    const val RETRY_AFTER_FAILURE = 5L * 60

    /** Pressing SYNC twice inside this window does nothing the second time. */
    const val MANUAL_COOLDOWN = 60L

    /** The optional background job's interval. BrightLedger's was once a day. */
    const val BACKGROUND_HOURS = 24L

    fun initialWindowStart(now: Long): Long = now - INITIAL_DAYS * DAY

    fun windowStart(lastSuccess: Long, now: Long): Long {
        if (lastSuccess <= 0L) return initialWindowStart(now)
        return minOf(lastSuccess - OVERLAP_DAYS * DAY, now)
            .coerceAtLeast(initialWindowStart(now))
    }

    fun keepSince(now: Long): Long = now - KEEP_DAYS * DAY

    fun showSince(now: Long): Long = now - SHOW_DAYS * DAY

    fun shouldAutoRefresh(lastSuccess: Long, lastAttempt: Long, now: Long): Boolean {
        if (now - lastSuccess < AUTO_REFRESH_AFTER) return false
        // A clock that moved backwards would otherwise block refreshing until it caught up.
        if (lastAttempt > now) return true
        return now - lastAttempt >= RETRY_AFTER_FAILURE
    }

    fun canManualRefresh(lastAttempt: Long, now: Long): Boolean =
        lastAttempt > now || now - lastAttempt >= MANUAL_COOLDOWN
}
