package com.crossclip.sync

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.Message
import org.json.JSONObject
import javax.crypto.spec.SecretKeySpec

class SyncService : Service() {

    companion object {
        var instance: SyncService? = null
        const val CHANNEL_ID = "cross_clip_service_channel"
        const val NOTIF_ID = 4040
        const val CAPTURE_NOTIF_ID = 4041
        const val CAPTURE_CHANNEL_ID = "cross_clip_capture_channel"
        const val ABLY_CHANNEL_NAME = "cross-clip-sync"
        const val DEVICE_ID = "android_phone"
        const val ACTION_TOGGLE_PAUSE = "com.crossclip.sync.ACTION_TOGGLE_PAUSE"
    }

    private var ably: AblyRealtime? = null
    private var ablyChannel: Channel? = null
    var lastKnownHash = ""
    var secretKey: SecretKeySpec? = null
    var isPaused = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isPaused) {
            Log.d("CrossClip", "Clipboard changed! Launching ClipCaptureActivity via fullScreenIntent...")
            mainHandler.post {
                launchCaptureActivity()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        startForeground(NOTIF_ID, buildStatusNotification())

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(clipListener)
        Log.d("CrossClip", "SyncService started. Clipboard listener attached.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_PAUSE) {
            isPaused = !isPaused
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.notify(NOTIF_ID, buildStatusNotification())
            Log.d("CrossClip", "Sync paused state: $isPaused")
            return START_STICKY
        }

        val apiKey = intent?.getStringExtra("API_KEY")
            ?: "ca7YEA.YRpXLw:CnnU8B8-VhcmBWb30ljl4uXWVEaxu6OLu8sNglwAW80"
        initAbly(apiKey)
        return START_STICKY
    }

    private fun initAbly(apiKey: String) {
        val secretPart = apiKey.substringAfterLast(":")
        secretKey = CryptoUtil.deriveKey(secretPart)

        Thread {
            try {
                val options = ClientOptions(apiKey).apply { autoConnect = true }
                ably = AblyRealtime(options)
                ablyChannel = ably?.channels?.get(ABLY_CHANNEL_NAME)
                ablyChannel?.subscribe("clip_sync") { message: Message ->
                    onRemoteMessage(message.data.toString())
                }
                Log.d("CrossClip", "Ably connected and subscribed!")
            } catch (e: Exception) {
                Log.e("CrossClip", "Ably connection failed", e)
            }
        }.start()
    }

    private fun onRemoteMessage(rawJson: String) {
        if (isPaused) return
        try {
            val json = JSONObject(rawJson)
            val sender = json.optString("sender")
            if (sender == DEVICE_ID) return

            val incomingHash = json.optString("hash")
            val payload = json.optString("payload")

            if (incomingHash.isNotBlank() && payload.isNotBlank() && incomingHash != lastKnownHash) {
                lastKnownHash = incomingHash
                val key = secretKey ?: return
                val plainText = CryptoUtil.decrypt(payload, key)
                mainHandler.post { writeToSystemClipboard(plainText) }
            }
        } catch (e: Exception) {
            Log.e("CrossClip", "Error in onRemoteMessage", e)
        }
    }

    private fun writeToSystemClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CrossClip", text))
            Log.d("CrossClip", "Updated phone clipboard: ${text.take(15)}...")
        } catch (e: Exception) {
            Log.e("CrossClip", "Error setting phone clipboard", e)
        }
    }

    /**
     * KEY MECHANISM: Launch ClipCaptureActivity via a fullScreenIntent notification.
     *
     * Android's fullScreenIntent is what incoming call screens use - it's guaranteed to
     * launch an activity even from background, bypassing all BAL restrictions.
     * The activity is transparent + finishes in <200ms, user never sees it.
     */
    private fun launchCaptureActivity() {
        val captureIntent = ClipCaptureActivity.getLaunchIntent(this)

        // fullScreenIntent PendingIntent - this is the magic that bypasses BAL restrictions
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            0,
            captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CAPTURE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("CrossClip")
            .setContentText("Syncing clipboard...")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setTimeoutAfter(500) // Auto-dismiss after 500ms
            .build()

        try {
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.notify(CAPTURE_NOTIF_ID, notification)
            Log.d("CrossClip", "fullScreenIntent notification fired for clip capture")

            // Cancel the notification after a short delay (activity already launched by then)
            mainHandler.postDelayed({
                notifManager.cancel(CAPTURE_NOTIF_ID)
            }, 600)
        } catch (e: Exception) {
            Log.e("CrossClip", "Error firing fullScreenIntent notification", e)
        }
    }

    fun sendLocalClip(text: String) {
        if (isPaused) return
        val h = CryptoUtil.sha256(text)
        if (h == lastKnownHash) return
        lastKnownHash = h

        val key = secretKey ?: return
        Thread {
            try {
                val encrypted = CryptoUtil.encrypt(text, key)
                val json = JSONObject().apply {
                    put("sender", DEVICE_ID)
                    put("hash", h)
                    put("payload", encrypted)
                }
                ablyChannel?.publish("clip_sync", json.toString())
                Log.d("CrossClip", "Published clip to PC! Hash: ${h.take(8)}")
            } catch (e: Exception) {
                Log.e("CrossClip", "Error sending clip", e)
            }
        }.start()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Main persistent notification - silent
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CrossClip Background Sync",
                    NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Silent background sync status"
                    setShowBadge(false)
                }
            )

            // Capture channel - HIGH importance so fullScreenIntent fires
            manager.createNotificationChannel(
                NotificationChannel(CAPTURE_CHANNEL_ID, "CrossClip Sync Events",
                    NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Used internally for clipboard capture"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }
    }

    private fun buildStatusNotification(): Notification {
        val toggleIntent = Intent(this, SyncService::class.java).apply {
            action = ACTION_TOGGLE_PAUSE
        }
        val pToggle = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isPaused) "Sync PAUSED (tap to resume)" else "Sync ACTIVE (tap to pause)"
        val actionTitle = if (isPaused) "▶ Resume" else "⏸ Pause"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CrossClip")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, actionTitle, pToggle)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.removePrimaryClipChangedListener(clipListener)
        ably?.close()
        instance = null
        Log.d("CrossClip", "SyncService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
