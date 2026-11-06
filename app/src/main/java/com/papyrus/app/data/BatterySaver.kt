package com.papyrus.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the system's battery saver is on, live: its current state first, then each change. The
 * receiver is registered only while the flow is collected. It listens to a system broadcast, so it is
 * not exported.
 */
fun batterySaverFlow(context: Context): Flow<Boolean> = callbackFlow {
    val power = context.getSystemService(PowerManager::class.java)
    trySend(power.isPowerSaveMode)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(power.isPowerSaveMode)
        }
    }
    ContextCompat.registerReceiver(
        context,
        receiver,
        IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    awaitClose { context.unregisterReceiver(receiver) }
}.distinctUntilChanged()
