package com.gios.brightledgerlite.data

import com.gios.brightledgerlite.ledger.SyncPolicy
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlin.time.Duration.Companion.hours

const val SYNC_JOB_KEY = "ledger-sync"

/** The optional once-a-day refresh, off until it is switched on in Settings. */
object BackgroundSync {
    fun schedule(ctx: SealedLightContext) {
        LightWork.enqueuePeriodic(ctx, SYNC_JOB_KEY, repeatInterval = SyncPolicy.BACKGROUND_HOURS.hours)
    }

    fun cancel(ctx: SealedLightContext) {
        LightWork.cancel(ctx, SYNC_JOB_KEY)
    }
}

/**
 * Refreshes the cache so the numbers are current when the tool opens. It goes through the
 * same rate gate as opening the tool, so it never adds a request on top of a recent one.
 * A network failure asks LightWork to retry later; a revoked credential does not, since
 * retrying cannot fix it.
 */
@LightJob(SYNC_JOB_KEY)
val ledgerSyncJob: LightJobHandler = { ctx, _ ->
    val repo = LedgerRepository.get(ctx)
    when (val outcome = repo.sync(LedgerRepository.Trigger.Background)) {
        is LedgerRepository.Outcome.Failed -> LightJobResult.Retry
        is LedgerRepository.Outcome.Ok -> LightJobResult.Success(emptyMap(), null, "synced")
        else -> LightJobResult.Success(emptyMap(), null, outcome.toString())
    }
}
