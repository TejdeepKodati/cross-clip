package com.crossclip.sync

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val DEFAULT_API_KEY = "ca7YEA.YRpXLw:CnnU8B8-VhcmBWb30ljl4uXWVEaxu6OLu8sNglwAW80"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("cross_clip_prefs", Context.MODE_PRIVATE)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
        }

        val title = TextView(this).apply {
            text = "CrossClip Cloud Settings"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 40)
        }
        layout.addView(title)

        val keyLabel = TextView(this).apply { text = "Ably Cloud API Key:" }
        layout.addView(keyLabel)

        val keyInput = EditText(this).apply {
            setText(prefs.getString("api_key", DEFAULT_API_KEY))
        }
        layout.addView(keyInput)

        val startBtn = Button(this).apply {
            text = "Start Background Sync"
            setPadding(0, 30, 0, 30)
            setOnClickListener {
                val apiKey = keyInput.text.toString().trim()
                if (apiKey.isBlank()) {
                    Toast.makeText(this@MainActivity, "Please enter your API key", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                prefs.edit().putString("api_key", apiKey).apply()

                val serviceIntent = Intent(this@MainActivity, SyncService::class.java).apply {
                    putExtra("API_KEY", apiKey)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                Toast.makeText(this@MainActivity, "CrossClip is active in the background!", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        layout.addView(startBtn)

        val adbNote = TextView(this).apply {
            text = "\n\nNote: For background clipboard reads on Android 10+, run the one-click 'setup_phone.bat' on your PC while your phone is plugged in via USB."
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
        }
        layout.addView(adbNote)

        setContentView(layout)
    }
}
