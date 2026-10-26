package com.crossclip.sync

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
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
        const val ABLY_CHANNEL_NAME = "cross-clip-sync"
        const val DEVICE_ID = "android_phone"
        const val ACTION_TOGGLE_PAUSE = "com.crossclip.sync.ACTION_TOGGLE_PAUSE"
    }

    private var ably: AblyRealtime? = null
    private var channel: Channel? = null
    private var lastKnownHash = ""
    private var secretKey: SecretKeySpec? = null
    private var isPaused = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isPaused) {
            Log.d("CrossClip", "Primary clip changed! Triggering silent window capture...")
            mainHandler.post {
                captureClipboardViaOverlay()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createSilentChannel()
        startForeground(NOTIF_ID, buildNotification())

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(clipListener)
        Log.d("CrossClip", "SyncService onCreate completed and listener attached.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_PAUSE) {
            isPaused = !isPaused
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.notify(NOTIF_ID, buildNotification())
            Log.d("CrossClip", "Sync paused state changed to: $isPaused")
            return START_STICKY
        }

        val apiKey = intent?.getStringExtra("API_KEY") ?: "ca7YEA.YRpXLw:CnnU8B8-VhcmBWb30ljl4uXWVEaxu6OLu8sNglwAW80"
        initAbly(apiKey)
        return START_STICKY
    }

    private fun initAbly(apiKey: String) {
        val secretPart = apiKey.substringAfterLast(":")
        secretKey = CryptoUtil.deriveKey(secretPart)

        Thread {
            try {
                val options = ClientOptions(apiKey).apply {
                    autoConnect = true
                }
                ably = AblyRealtime(options)
                channel = ably?.channels?.get(ABLY_CHANNEL_NAME)

                channel?.subscribe("clip_sync") { message: Message ->
                    onRemoteMessage(message.data.toString())
                }
                Log.d("CrossClip", "Connected and subscribed to Ably!")
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

                mainHandler.post {
                    writeToSystemClipboard(plainText)
                }
            }
        } catch (e: Exception) {
            Log.e("CrossClip", "Error in onRemoteMessage", e)
        }
    }

    private fun writeToSystemClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("CrossClip", text)
            clipboard.setPrimaryClip(clip)
            Log.d("CrossClip", "Updated phone clipboard with remote text (${text.take(15)}...)")
        } catch (e: Exception) {
            Log.e("CrossClip", "Error setting phone clipboard", e)
        }
    }

    private fun captureClipboardViaOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dummyView = View(this)

        val params = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        var removed = false
        fun safeRemove() {
            if (!removed) {
                removed = true
                try {
                    wm.removeView(dummyView)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        dummyView.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    if (clipboard.hasPrimaryClip()) {
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val text = clip.getItemAt(0).coerceToText(this).toString()
                            Log.d("CrossClip", "Captured clip via overlay focus: ${text.take(20)}...")
                            if (text.isNotBlank()) {
                                sendLocalClip(text)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CrossClip", "Error reading clip during overlay focus", e)
                } finally {
                    safeRemove()
                }
            }
        }

        try {
            wm.addView(dummyView, params)
            // Safety timeout: remove view after 250ms
            mainHandler.postDelayed({ safeRemove() }, 250)
        } catch (e: Exception) {
            Log.e("CrossClip", "Error adding overlay window", e)
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
                channel?.publish("clip_sync", json.toString())
                Log.d("CrossClip", "Published phone clipboard to PC! Hash: ${h.take(8)}")
            } catch (e: Exception) {
                Log.e("CrossClip", "Error sending clip", e)
            }
        }.start()
    }

    private fun createSilentChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CrossClip Background Sync",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent background synchronization"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val toggleIntent = Intent(this, SyncService::class.java).apply {
            action = ACTION_TOGGLE_PAUSE
        }
        val pToggle = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isPaused) "Sync is PAUSED (Tap to Resume)" else "Sync is ACTIVE (Tap to Pause)"
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
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
