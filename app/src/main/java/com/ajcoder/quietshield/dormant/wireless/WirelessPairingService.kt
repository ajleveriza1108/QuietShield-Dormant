package com.ajcoder.quietshield.dormant.wireless

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import com.ajcoder.quietshield.dormant.MainActivity
import com.ajcoder.quietshield.dormant.R
import com.ajcoder.quietshield.dormant.data.PolicyRepository
import com.ajcoder.quietshield.dormant.service.DormantMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps discovery alive while Android's Wireless Debugging screen is open.
 * The user stays in Settings and enters only the six-digit code through the
 * notification; the changing address and port are discovered automatically.
 */
class WirelessPairingService : Service(), WirelessPairingDiscovery.Callback {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager
    private lateinit var discovery: WirelessPairingDiscovery
    private lateinit var activationManager: WirelessActivationManager

    @Volatile
    private var endpoint: WirelessPairingDiscovery.Endpoint? = null

    @Volatile
    private var pairingInProgress = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        activationManager = WirelessActivationManager(applicationContext)
        discovery = WirelessPairingDiscovery(applicationContext, this)
        createNotificationChannel()
        startPairingForeground(
            title = "Wireless setup is waiting",
            text = "Open Wireless Debugging and choose Pair device with pairing code.",
            allowReply = false,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SUBMIT_CODE -> submitPairingCode(intent.getStringExtra(EXTRA_CODE).orEmpty())
            ACTION_CANCEL -> stopSelf()
            else -> discovery.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        discovery.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSearching() {
        if (endpoint == null && !pairingInProgress) {
            updatePairingNotification(
                title = "Waiting for Android's pairing screen",
                text = "Keep Pair device with pairing code open. Dormant will find the changing port automatically.",
                allowReply = false,
            )
        }
    }

    override fun onEndpointChanged(endpoint: WirelessPairingDiscovery.Endpoint) {
        this.endpoint = endpoint
        if (!pairingInProgress) {
            updatePairingNotification(
                title = "Enter the 6-digit code",
                text = "Type only the code shown in Wireless Debugging, then tap Pair.",
                allowReply = true,
            )
        }
    }

    override fun onEndpointLost() {
        endpoint = null
        if (!pairingInProgress) {
            updatePairingNotification(
                title = "Pairing screen changed",
                text = "Open Pair device with pairing code again. Dormant will find the new port.",
                allowReply = false,
            )
        }
    }

    override fun onFailure(message: String) {
        updatePairingNotification(
            title = "Wireless setup needs attention",
            text = message,
            allowReply = false,
        )
    }

    private fun submitPairingCode(rawCode: String) {
        if (pairingInProgress) return
        val code = rawCode.filter(Char::isDigit).take(6)
        if (code.length != 6) {
            updatePairingNotification(
                title = "Enter all 6 digits",
                text = "Open the notification again and enter the complete code.",
                allowReply = endpoint != null,
            )
            return
        }
        val selectedEndpoint = endpoint
        if (selectedEndpoint == null) {
            updatePairingNotification(
                title = "Pairing screen not found yet",
                text = "Keep Android's pairing-code screen open until Dormant asks for the code.",
                allowReply = false,
            )
            return
        }

        pairingInProgress = true
        updatePairingNotification(
            title = "Pairing QuietShield Dormant",
            text = "Keep the Wireless Debugging screen open for a moment.",
            allowReply = false,
        )
        serviceScope.launch {
            val result = activationManager.pairAndStart(
                host = selectedEndpoint.host,
                port = selectedEndpoint.port,
                pairingCode = code,
            )
            pairingInProgress = false
            when (result) {
                WirelessActivationResult.Success -> completeSetup()
                is WirelessActivationResult.Failure -> {
                    updatePairingNotification(
                        title = "Pairing did not finish",
                        text = "The code or pairing screen may have changed. Open a new pairing code and try again.",
                        allowReply = endpoint != null,
                    )
                }
            }
        }
    }

    private suspend fun completeSetup() {
        discovery.stop()
        val usageReady = hasUsageAccess()
        if (usageReady) {
            PolicyRepository(applicationContext).setAutomaticClosing(true)
            DormantMonitorService.start(applicationContext)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val message = if (usageReady) {
            "Automatic closing is on. You can return to Dormant."
        } else {
            "Wireless setup is saved. Open Dormant and allow app activity access."
        }
        notificationManager.notify(
            COMPLETION_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_quick_dormant)
                .setContentTitle("QuietShield Dormant is ready")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
        delay(250L)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            applicationInfo.uid,
            packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun startPairingForeground(title: String, text: String, allowReply: Boolean) {
        val notification = buildNotification(title, text, allowReply)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, PAIRING_NOTIFICATION_ID, notification, type)
    }

    private fun updatePairingNotification(title: String, text: String, allowReply: Boolean) {
        notificationManager.notify(
            PAIRING_NOTIFICATION_ID,
            buildNotification(title, text, allowReply),
        )
    }

    private fun buildNotification(title: String, text: String, allowReply: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_dormant)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (allowReply) {
            val replyIntent = Intent(this, WirelessPairingReplyReceiver::class.java).apply {
                action = ACTION_NOTIFICATION_REPLY
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                this,
                2,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
                .setLabel("6-digit code")
                .build()
            val replyAction = NotificationCompat.Action.Builder(
                R.drawable.ic_quick_dormant,
                "Enter code",
                replyPendingIntent,
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(false)
                .build()
            builder.addAction(replyAction)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Wireless setup",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Shows the secure code entry used to pair QuietShield Dormant."
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val KEY_PAIRING_CODE = "quietshield_pairing_code"

        private const val CHANNEL_ID = "quietshield_dormant_wireless_pairing"
        private const val PAIRING_NOTIFICATION_ID = 47541
        private const val COMPLETION_NOTIFICATION_ID = 47542
        private const val ACTION_START = "com.ajcoder.quietshield.dormant.action.START_WIRELESS_PAIRING"
        private const val ACTION_SUBMIT_CODE = "com.ajcoder.quietshield.dormant.action.SUBMIT_WIRELESS_CODE"
        private const val ACTION_CANCEL = "com.ajcoder.quietshield.dormant.action.CANCEL_WIRELESS_PAIRING"
        private const val ACTION_NOTIFICATION_REPLY = "com.ajcoder.quietshield.dormant.action.WIRELESS_REPLY"
        private const val EXTRA_CODE = "pairing_code"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WirelessPairingService::class.java).setAction(ACTION_START),
            )
        }

        fun submitCode(context: Context, code: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WirelessPairingService::class.java)
                    .setAction(ACTION_SUBMIT_CODE)
                    .putExtra(EXTRA_CODE, code),
            )
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, WirelessPairingService::class.java).setAction(ACTION_CANCEL),
            )
        }

        fun openWirelessDebugging(context: Context) {
            val direct = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
            val fallback = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            val intent = if (direct.resolveActivity(context.packageManager) != null) direct else fallback
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
