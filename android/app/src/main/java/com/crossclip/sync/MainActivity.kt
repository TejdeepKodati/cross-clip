package com.crossclip.sync

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.crossclip.sync.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupListeners()
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(SyncService.PREFS_NAME, Context.MODE_PRIVATE)
        binding.etServerUrl.setText(prefs.getString(SyncService.KEY_SERVER_URL, "https://cross-clip-relay.tejdeepkodati996.workers.dev"))
        binding.etPairingCode.setText(prefs.getString(SyncService.KEY_SECRET, ""))
        binding.switchNotifications.isChecked = prefs.getBoolean(SyncService.KEY_NOTIFS, false)
    }

    private fun setupListeners() {
        // Auto-detect JSON pasted from QR Code
        binding.etPairingCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: return
                if (text.startsWith("{") && text.endsWith("}")) {
                    try {
                        val json = JSONObject(text)
                        val server = json.optString("server")
                        val secret = json.optString("secret")
                        if (server.isNotEmpty()) binding.etServerUrl.setText(server)
                        if (secret.isNotEmpty()) binding.etPairingCode.setText(secret)
                    } catch (_: Exception) {}
                }
            }
        })

        binding.btnSavePairing.setOnClickListener {
            val server = binding.etServerUrl.text.toString().trim()
            val secret = binding.etPairingCode.text.toString().trim()

            if (server.isEmpty() || secret.isEmpty()) {
                Toast.makeText(this, "Please enter both Server URL and Pairing Code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefs = getSharedPreferences(SyncService.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(SyncService.KEY_SERVER_URL, server)
                .putString(SyncService.KEY_SECRET, secret)
                .apply()

            val serviceIntent = Intent(this, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, "Settings saved! Connecting...", Toast.LENGTH_SHORT).show()
            updateUi()
        }

        binding.btnTogglePause.setOnClickListener {
            val service = SyncService.instance
            if (service != null) {
                service.setPaused(!service.isPaused())
            } else {
                val prefs = getSharedPreferences(SyncService.PREFS_NAME, Context.MODE_PRIVATE)
                val isPaused = prefs.getBoolean(SyncService.KEY_PAUSED, false)
                prefs.edit().putBoolean(SyncService.KEY_PAUSED, !isPaused).apply()
            }
            updateUi()
        }

        binding.btnBatteryOpt.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "Please allow background activity in device settings", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences(SyncService.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(SyncService.KEY_NOTIFS, isChecked).apply()
        }
    }

    fun updateUi() {
        runOnUiThread {
            val service = SyncService.instance
            val prefs = getSharedPreferences(SyncService.PREFS_NAME, Context.MODE_PRIVATE)
            val isPaused = service?.isPaused() ?: prefs.getBoolean(SyncService.KEY_PAUSED, false)
            val secret = prefs.getString(SyncService.KEY_SECRET, "")

            if (isPaused) {
                binding.tvStatus.text = "⏸ Paused (Sync Off)"
                binding.tvStatus.setTextColor(Color.parseColor("#FFC107"))
                binding.btnTogglePause.text = "Resume Sync"
            } else if (secret.isNullOrEmpty()) {
                binding.tvStatus.text = "● Not Configured"
                binding.tvStatus.setTextColor(Color.parseColor("#DC3545"))
                binding.btnTogglePause.text = "Pause Sync"
            } else {
                binding.tvStatus.text = "● Active & Syncing"
                binding.tvStatus.setTextColor(Color.parseColor("#28A745"))
                binding.btnTogglePause.text = "Pause Sync"
            }

            // Check ADB permissions
            val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this)
            } else true

            if (hasOverlay) {
                binding.tvChecklistAdb.text = "✓ ADB Permissions: GRANTED"
                binding.tvChecklistAdb.setTextColor(Color.parseColor("#28A745"))
            } else {
                binding.tvChecklistAdb.text = "⚠ ADB Permissions: Pending setup-android.bat"
                binding.tvChecklistAdb.setTextColor(Color.parseColor("#E65100"))
            }

            // Check Battery whitelist
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isIgnoringBatteryOptimizations(packageName)
            } else true

            if (isIgnoringBattery) {
                binding.tvChecklistBattery.text = "✓ Battery Whitelist: GRANTED"
                binding.tvChecklistBattery.setTextColor(Color.parseColor("#28A745"))
            } else {
                binding.tvChecklistBattery.text = "⚠ Battery Whitelist: Tap button above"
                binding.tvChecklistBattery.setTextColor(Color.parseColor("#E65100"))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
