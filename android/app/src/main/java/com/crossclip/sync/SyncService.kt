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
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.Message
import org.json.JSONObject
import javax.crypto.spec.SecretKeySpec

class SyncService : Service() {

    companion object {
        var instance: SyncService? = null
        const val CHANNEL_ID = "cross_clip_silent_channel"
        const val NOTIF_ID = 4040
        const val ABLY_CHANNEL_NAME = "cross-clip-sync"
        const val DEVICE_ID = "android_phone"
    }

    private var ably: AblyRealtime? = null
    private var channel: Channel? = null
    private var lastKnownHash = ""
    private var secretKey: SecretKeySpec? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        Log.d("CrossClip", "System OnPrimaryClipChangedListener triggered! Launching trampoline...")
        launchFocusTrampoline()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createSilentChannel()
        startForeground(NOTIF_ID, buildNotification())

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(clipListener)
        Log.d("CrossClip", "SyncService onCreate completed, listener attached.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                Log.d("CrossClip", "Ably connected and subscribed on Android!")
            } catch (e: Exception) {
                Log.e("CrossClip", "Ably init error", e)
            }
        }.start()
    }

    private fun onRemoteMessage(rawJson: String) {
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
                Log.d("CrossClip", "Received remote clip from PC: ${plainText.take(20)}...")

                mainHandler.post {
                    writeToSystemClipboard(plainText)
                }
            }
        } catch (e: Exception) {
            Log.e("CrossClip", "Error processing remote message", e)
        }
    }

    private fun writeToSystemClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("CrossClip", text)
            clipboard.setPrimaryClip(clip)
            Log.d("CrossClip", "Silently wrote to Android clipboard container!")
        } catch (e: Exception) {
            Log.e("CrossClip", "Failed writing to Android clipboard", e)
        }
    }

    fun sendLocalClip(text: String) {
        val h = CryptoUtil.sha256(text)
        if (h == lastKnownHash) {
            Log.d("CrossClip", "Skipping sendLocalClip: hash matches lastKnownHash")
            return
        }
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
                Log.d("CrossClip", "Published local Android clip to Ably! Hash: ${h.take(8)}")
            } catch (e: Exception) {
                Log.e("CrossClip", "Error publishing local clip", e)
            }
        }.start()
    }

    private fun launchFocusTrampoline() {
        val intent = Intent(this, TransparentActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        startActivity(intent)
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CrossClip Active")
            .setContentText("Connected to cloud relay")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
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
