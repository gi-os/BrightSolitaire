package com.thelightphone.solitaire

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * Solitaire has no server and takes no push notifications, so both hooks are
 * deliberately empty. The object still has to exist for the SDK's KSP
 * processor to generate a registry.
 */
@EntryPoint
object SolitaireEntryPoint : LightEntryPoint {

    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) = Unit

    override suspend fun onPushNotification(data: ByteArray) = Unit
}
