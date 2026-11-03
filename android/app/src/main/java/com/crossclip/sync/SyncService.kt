package com.crossclip.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SyncService : Service() {

    companion object {
        var instance: SyncService? = null
        const val PREFS_NAME = "cross_clip_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_SECRET = "master_secret"
        const val KEY_PAUSED = "is_paused"
        const val KEY_NOTIFS = "show_notifications"

        const val CHANNEL_ID = "cross_clip_status_channel"
        const val NOTIF_ID = 5050
        const val CAPTURE_CHANNEL_ID = "cross_clip_capture_channel"
        const val CAPTURE_NOTIF_ID = 5051

        const val ACTION_TOGGLE_PAUSE = "com.crossclip.sync.ACTION_TOGGLE_PAUSE"
    }

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var aesKey: ByteArray? = null
    private var roomId: String? = null
    private var lastKnownHash: String = ""
    private var isPaused = false
    private var showToasts = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempt = 0

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isPaused) {
            launchCaptureActivity()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isPaused = prefs.getBoolean(KEY_PAUSED, false)
        showToasts = prefs.getBoolean(KEY_NOTIFS, false)

        startForeground(NOTIF_ID, buildStatusNotification())

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(clipListener)

        loadAndConnect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_PAUSE) {
            setPaused(!isPaused)
            return START_STICKY
        }

        // Reload configuration if started with new params
        loadAndConnect()
        return START_STICKY
    }

    fun setPaused(paused: Boolean) {
        if (isPaused == paused) return
        isPaused = paused

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PAUSED, isPaused).apply()

        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify(NOTIF_ID, buildStatusNotification())

        MainActivity.instance?.updateUi()

        if (isPaused) {
            disconnectWebSocket()
            Log.d("CrossClip", "Sync paused. WebSocket disconnected.")
        } else {
            reconnectAttempt = 0
            connectWebSocket()
            Log.d("CrossClip", "Sync resumed. Connecting WebSocket.")
        }
    }

    fun isPaused(): Boolean = isPaused

    private fun loadAndConnect() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val secretBase32 = prefs.getString(KEY_SECRET, null)
        val serverUrl = prefs.getString(KEY_SERVER_URL, null)
        showToasts = prefs.getBoolean(KEY_NOTIFS, false)

        if (secretBase32.isNullOrEmpty() || serverUrl.isNullOrEmpty()) {
            return
        }

        try {
            val secretBytes = CryptoUtil.decodeBase32(secretBase32)
            val (derivedRoom, derivedKey) = CryptoUtil.deriveKeys(secretBytes)
            roomId = derivedRoom
            aesKey = derivedKey

            if (!isPaused) {
                connectWebSocket()
            }
        } catch (e: Exception) {
            Log.e("CrossClip", "Error initializing keys", e)
        }
    }

    private fun connectWebSocket() {
        if (isPaused) return
        val currentRoom = roomId ?: return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var serverUrl = prefs.getString(KEY_SERVER_URL, "")?.trim()?.trimEnd('/') ?: return

        if (serverUrl.startsWith("http://", ignoreCase = true)) {
            serverUrl = "ws://" + serverUrl.substring(7)
        } else if (serverUrl.startsWith("https://", ignoreCase = true)) {
            serverUrl = "wss://" + serverUrl.substring(8)
        } else if (!serverUrl.startsWith("ws://", ignoreCase = true) && !serverUrl.startsWith("wss://", ignoreCase = true)) {
            serverUrl = "wss://$serverUrl"
        }

        val fullUrl = "$serverUrl/ws/$currentRoom"
        disconnectWebSocket()

        client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(fullUrl).build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d("CrossClip", "WebSocket connected to room: $currentRoom")
                reconnectAttempt = 0
                MainActivity.instance?.updateUi()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                onIncomingMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w("CrossClip", "WebSocket connection failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d("CrossClip", "WebSocket closed: $code / $reason")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isPaused) return
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }

        reconnectAttempt++
        val delaySec = minOf(30, (1 shl minOf(reconnectAttempt, 5)))
        Log.d("CrossClip", "Scheduling reconnect in ${delaySec}s (attempt $reconnectAttempt)")

        reconnectRunnable = Runnable {
            if (!isPaused) {
                connectWebSocket()
            }
        }
        mainHandler.postDelayed(reconnectRunnable!!, delaySec * 1000L)
        MainActivity.instance?.updateUi()
    }

    private fun disconnectWebSocket() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            webSocket?.close(1000, "Service disconnect")
        } catch (_: Exception) {}
        webSocket = null
    }

    private fun onIncomingMessage(jsonStr: String) {
        if (isPaused) return
        val key = aesKey ?: return

        try {
            val json = JSONObject(jsonStr)
            val type = json.optString("type")
            if (type == "pong") return

            if (type == "clip") {
                val nonce = json.optString("nonce")
                val ciphertext = json.optString("ciphertext")
                val timestamp = json.optLong("timestamp")

                val clip = CryptoUtil.decrypt(nonce, ciphertext, timestamp, key) ?: return
                if (clip.sender == "android") return // Ignore our own clips

                if (clip.hash.isNotEmpty() && clip.hash != lastKnownHash) {
                    lastKnownHash = clip.hash
                    mainHandler.post {
                        writeToSystemClipboard(clip.text)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CrossClip", "Error parsing incoming clip", e)
        }
    }

    private fun writeToSystemClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("cross-clip", text))
            Log.d("CrossClip", "Updated phone clipboard from PC: ${text.take(20)}...")

            if (showToasts) {
                val preview = if (text.length > 30) text.take(30) + "..." else text
                Toast.makeText(this, "Clipboard synced: $preview", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("CrossClip", "Error setting phone clipboard", e)
        }
    }

    fun sendLocalClip(text: String) {
        if (isPaused) return
        val key = aesKey ?: return

        val hash = CryptoUtil.sha256(text)
        if (hash == lastKnownHash) return
        lastKnownHash = hash

        Thread {
            try {
                val envelopeJson = CryptoUtil.encrypt(text, "android", key)
                val sent = webSocket?.send(envelopeJson) ?: false
                if (sent) {
                    Log.d("CrossClip", "Sent clip to PC: ${text.take(20)}...")
                }
            } catch (e: Exception) {
                Log.e("CrossClip", "Failed to send local clip", e)
            }
        }.start()
    }

    private fun launchCaptureActivity() {
        val captureIntent = Intent(this, ClipCaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }

        // Direct startActivity launch
        try {
            startActivity(captureIntent)
        } catch (_: Exception) {}

        // Fallback: fullScreenIntent notification to bypass ColorOS background restrictions
        try {
            val fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                0,
                captureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CAPTURE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("cross-clip")
                .setContentText("Syncing...")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setAutoCancel(true)
                .setTimeoutAfter(300)
                .build()

            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.notify(CAPTURE_NOTIF_ID, notification)
            mainHandler.postDelayed({ notifManager.cancel(CAPTURE_NOTIF_ID) }, 400)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Minimal importance persistent notification
            val statusChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(statusChannel)

            // Capture channel for fullScreenIntent
            val captureChannel = NotificationChannel(
                CAPTURE_CHANNEL_ID,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(captureChannel)
        }
    }

    private fun buildStatusNotification(): Notification {
        val toggleIntent = Intent(this, SyncService::class.java).apply {
            action = ACTION_TOGGLE_PAUSE
        }
        val pToggle = PendingIntent.getService(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isPaused) {
            "cross-clip is paused, tap to resume"
        } else {
            "cross-clip is active and running"
        }
        val actionText = if (isPaused) "▶ Resume" else "⏸ Pause"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("cross-clip")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pToggle) // Entire notification body toggles pause/resume!
            .addAction(android.R.drawable.ic_media_pause, actionText, pToggle)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.removePrimaryClipChangedListener(clipListener)
        disconnectWebSocket()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
