package com.betrome.digitalexplorer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var settings: Settings
    private lateinit var cols: EditText
    private lateinit var rows: EditText
    private lateinit var delay: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 15f; setPadding(0, 16, 0, 4) }
        fun field(v: Int) = EditText(this).apply { setText(v.toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

        root.addView(TextView(this).apply { text = "Digital Explorer"; textSize = 24f })
        root.addView(label("Auto-clears blocks and collects items in the Digimon Up grid mini-game."))
        status = label("")
        root.addView(status)
        root.addView(btn("1. Allow draw-over-apps") {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("2. Enable accessibility service") {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(label("Grid columns / rows / delay between taps (ms)"))
        cols = field(settings.cols); rows = field(settings.rows); delay = field(settings.stepDelayMs)
        root.addView(cols); root.addView(rows); root.addView(delay)
        root.addView(btn("3. Start overlay") { start() })
        root.addView(label("Then open the game's grid screen, tap Calibrate once, Labels to see what it detects, and Start."))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val overlay = AndroidSettings.canDrawOverlays(this)
        val access = TapService.instance != null
        status.text = "Overlay permission: ${if (overlay) "OK" else "missing"}\nAccessibility service: ${if (access) "OK" else "off"}"
    }

    private fun start() {
        if (!AndroidSettings.canDrawOverlays(this)) return
        cols.text.toString().toIntOrNull()?.takeIf { it > 0 }?.let { settings.cols = it }
        rows.text.toString().toIntOrNull()?.takeIf { it > 0 }?.let { settings.rows = it }
        delay.text.toString().toIntOrNull()?.takeIf { it >= 100 }?.let { settings.stepDelayMs = it }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, OverlayService::class.java).putExtra("resultCode", resultCode).putExtra("data", data)
            )
            moveTaskToBack(true)
        }
    }
}
