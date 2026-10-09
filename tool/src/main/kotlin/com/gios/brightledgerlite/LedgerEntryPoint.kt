package com.gios.brightledgerlite

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * Ledger Lite takes no push messages: SimpleFIN has no push, and the tool only ever asks.
 * The object exists so the SDK's KSP processor has an entry point to register.
 */
@EntryPoint
object LedgerEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) = Unit

    override suspend fun onPushNotification(data: ByteArray) = Unit
}
