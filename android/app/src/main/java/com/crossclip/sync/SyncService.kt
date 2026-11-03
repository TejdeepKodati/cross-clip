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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class SyncService : Service() {

    companion object {
        var instance: SyncService? = null
        const val TAG = "CrossClip"
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
    private val socketLock = Any()

    private var aesKey: ByteArray? = null
    private var roomId: String? = null
    private var lastKnownHash: String = ""
    private var isPaused = false
    private var showToasts = false
    private var lastCaptureLaunchTime: Long = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempt = 0

    private var logcatProcess: Process? = null
    private var logcatThread: Thread? = null

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

        startLogcatWatcher()
        loadAndConnect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_PAUSE) {
            setPaused(!isPaused)
            return START_STICKY
        }

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
            stopLogcatWatcher()
            Log.d(TAG, "Sync paused. WebSocket disconnected.")
        } else {
            reconnectAttempt = 0
            startLogcatWatcher()
            connectWebSocket()
            Log.d(TAG, "Sync resumed. Connecting WebSocket.")
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
            Log.e(TAG, "Error initializing keys", e)
        }
    }

    private fun connectWebSocket() {
        synchronized(socketLock) {
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

            // Cancel any pending reconnect tasks
            reconnectRunnable?.let { mainHandler.removeCallbacks(it) }

            // Gracefully close any existing active socket
            val oldWs = webSocket
            webSocket = null
            if (oldWs != null) {
                try {
                    oldWs.close(1000, "Normal cleanup")
                } catch (_: Exception) {}
            }

            if (client == null) {
                client = OkHttpClient.Builder()
                    .pingInterval(30, TimeUnit.SECONDS)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
            }

            val request = Request.Builder().url(fullUrl).build()

            val newWs = client!!.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    synchronized(socketLock) {
                        if (webSocket !== ws) {
                            ws.close(1000, "Obsolete")
                            return
                        }
                    }
                    Log.d(TAG, "WebSocket connected to room: $currentRoom")
                    reconnectAttempt = 0
                    MainActivity.instance?.updateUi()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    synchronized(socketLock) {
                        if (webSocket !== ws) return
                    }
                    onIncomingMessage(text)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    synchronized(socketLock) {
                        if (webSocket !== ws) return
                        webSocket = null
                    }
                    Log.w(TAG, "WebSocket connection failure: ${t.javaClass.simpleName}: ${t.message}")
                    scheduleReconnect()
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    synchronized(socketLock) {
                        if (webSocket !== ws) return
                        webSocket = null
                    }
                    Log.d(TAG, "WebSocket closed: $code / $reason")
                    if (code != 1000 && !isPaused) {
                        scheduleReconnect()
                    }
                }
            })

            webSocket = newWs
        }
    }

    private fun scheduleReconnect() {
        if (isPaused) return
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }

        reconnectAttempt++
        val delaySec = minOf(30, (1 shl minOf(reconnectAttempt, 5)))
        Log.d(TAG, "Scheduling reconnect in ${delaySec}s (attempt $reconnectAttempt)")

        reconnectRunnable = Runnable {
            if (!isPaused) {
                connectWebSocket()
            }
        }
        mainHandler.postDelayed(reconnectRunnable!!, delaySec * 1000L)
        MainActivity.instance?.updateUi()
    }

    private fun disconnectWebSocket() {
        synchronized(socketLock) {
            reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
            val ws = webSocket
            webSocket = null
            if (ws != null) {
                try {
                    ws.close(1000, "Service disconnect")
                } catch (_: Exception) {}
            }
        }
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
                    mainHandler.post {
                        writeToSystemClipboard(clip.text, clip.hash)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming clip", e)
        }
    }

    private fun writeToSystemClipboard(text: String, hash: String) {
        try {
            // Update lastKnownHash BEFORE setting system clipboard so our own listener suppresses it
            lastKnownHash = hash

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("cross-clip", text))
            Log.d(TAG, "Updated phone clipboard from PC: ${text.take(20)}...")

            if (showToasts) {
                val preview = if (text.length > 30) text.take(30) + "..." else text
                Toast.makeText(this, "Clipboard synced: $preview", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting phone clipboard", e)
        }
    }

    fun sendLocalClip(text: String) {
        if (isPaused) return
        val key = aesKey ?: return

        val hash = CryptoUtil.sha256(text)
        if (hash == lastKnownHash) {
            Log.d(TAG, "Suppressed sending duplicate/echo clip: ${text.take(15)}...")
            return
        }
        lastKnownHash = hash

        Thread {
            try {
                val envelopeJson = CryptoUtil.encrypt(text, "android", key)
                val ws = webSocket
                if (ws != null) {
                    val sent = ws.send(envelopeJson)
                    Log.d(TAG, "Sent clip to PC (${text.length} chars, sent=$sent)")
                } else {
                    Log.w(TAG, "Cannot send clip: WebSocket is null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send local clip", e)
            }
        }.start()
    }

    fun launchCaptureActivity() {
        if (isPaused) return
        val now = System.currentTimeMillis()
        if (now - lastCaptureLaunchTime < 350) return // Debounce 350ms
        lastCaptureLaunchTime = now

        val captureIntent = Intent(this, ClipCaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // Direct startActivity launch
        try {
            startActivity(captureIntent)
            return
        } catch (e: Exception) {
            Log.d(TAG, "Direct startActivity failed, trying notification fallback: ${e.message}")
        }

        // Fallback: fullScreenIntent notification to bypass background start restrictions
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ClipCaptureActivity", e)
        }
    }

    private fun startLogcatWatcher() {
        stopLogcatWatcher()
        logcatThread = Thread({
            try {
                // Clear existing logcat buffer first so we only process new events
                try {
                    Runtime.getRuntime().exec("logcat -c").waitFor()
                } catch (_: Exception) {}

                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "brief", "-b", "system", "-b", "main"))
                logcatProcess = process
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String? = null

                Log.d(TAG, "LogcatWatcher active and streaming logs.")

                while (!Thread.currentThread().isInterrupted && reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (isPaused) continue

                    // Ignore log lines from our own app
                    if (l.contains("com.crossclip.sync") || l.contains("CrossClip")) continue

                    val lower = l.lowercase()
                    // Match clipboard change log events
                    if (lower.contains("clipboard") || lower.contains("primaryclip") || lower.contains("denying clipboard access")) {
                        Log.d(TAG, "LogcatWatcher clipboard event: $l")
                        mainHandler.post {
                            launchCaptureActivity()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "LogcatWatcher ended: ${e.message}")
            }
        }, "CrossClip-LogcatWatcher").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopLogcatWatcher() {
        try {
            logcatThread?.interrupt()
            logcatProcess?.destroy()
        } catch (_: Exception) {}
        logcatProcess = null
        logcatThread = null
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
            .setContentIntent(pToggle)
            .addAction(android.R.drawable.ic_media_pause, actionText, pToggle)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.removePrimaryClipChangedListener(clipListener)
        stopLogcatWatcher()
        disconnectWebSocket()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
