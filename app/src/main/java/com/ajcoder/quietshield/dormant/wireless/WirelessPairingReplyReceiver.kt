package com.ajcoder.quietshield.dormant.wireless

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Receives the six-digit code entered directly in the Dormant notification. */
class WirelessPairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(WirelessPairingService.KEY_PAIRING_CODE)
            ?.toString()
            .orEmpty()
            .filter(Char::isDigit)
            .take(6)
        WirelessPairingService.submitCode(context, code)
    }
}
