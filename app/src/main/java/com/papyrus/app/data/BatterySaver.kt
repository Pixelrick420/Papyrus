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
