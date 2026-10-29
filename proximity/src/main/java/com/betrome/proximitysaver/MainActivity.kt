package com.betrome.proximitysaver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), SensorEventListener {
    private lateinit var settings: Settings
    private lateinit var sm: SensorManager
    private var sensor: Sensor? = null
    private lateinit var enabled: Switch
    private lateinit var reading: TextView
    private lateinit var sensLabel: TextView
    private lateinit var delayLabel: TextView
    private var lastValue = Float.NaN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        sm = getSystemService(SensorManager::class.java)
        sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 15f; setPadding(0, 16, 0, 4) }
        fun slider(max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
            this.max = max; progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = onChange(p)
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }

        root.addView(TextView(this).apply { text = "Proximity Saver"; textSize = 24f })
        root.addView(label("Turns the screen off while the proximity sensor is covered, so games like Pokémon GO or " +
            "Digimon Up keep running in your pocket with the display dark. Uncover it to turn the screen back on."))

        enabled = Switch(this).apply {
            text = "Enabled"; textSize = 18f; setPadding(0, 24, 0, 8)
            setOnCheckedChangeListener { _, on -> setActive(on) }
        }
        root.addView(enabled)

        reading = label("")
        root.addView(reading)

        sensLabel = label("")
        root.addView(sensLabel)
        root.addView(slider(99, settings.sensitivity - 1) { settings.sensitivity = it + 1; refresh() })

        delayLabel = label("")
        root.addView(delayLabel)
        // 0 .. 10 s in 0.25 s steps.
        root.addView(slider(40, settings.delayMs / 250) { settings.delayMs = it * 250; refresh() })

        root.addView(label("Tips: if the screen turns off when nothing is covering the sensor, raise the delay " +
            "or lower the sensitivity. Many sensors only report two values (covered / clear), in which case only " +
            "the delay makes a difference. Turn it off here, from the notification, or with the " +
            "Proximity Saver tile in Quick Settings (edit the tiles to add it)."))
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun setActive(on: Boolean) {
        if (on == settings.enabled && on == ProximityService.running) return
        settings.enabled = on
        val i = Intent(this, ProximityService::class.java)
        if (on) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            startForegroundService(i)
        } else {
            stopService(i)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-sync with the service: it may have been turned off from the notification or killed.
        if (settings.enabled && !ProximityService.running) setActive(true)
        enabled.isChecked = settings.enabled
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        refresh()
    }

    override fun onPause() {
        sm.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(e: SensorEvent) { lastValue = e.values[0]; refresh() }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    private fun refresh() {
        val s = sensor
        if (s == null) {
            reading.text = "No proximity sensor found on this phone."
            enabled.isEnabled = false
            return
        }
        val threshold = settings.thresholdCm(s.maximumRange)
        val state = when {
            lastValue.isNaN() -> "waiting for sensor..."
            lastValue < threshold -> "%.1f cm - COVERED".format(lastValue)
            else -> "%.1f cm - clear".format(lastValue)
        }
        reading.text = "Live sensor reading: $state\n(${s.name}, max ${"%.1f".format(s.maximumRange)} cm)"
        sensLabel.text = "Sensitivity: ${settings.sensitivity}%% - counts as covered below %.2f cm".format(threshold)
        delayLabel.text = "Wait before screen off: %.2f s".format(settings.delayMs / 1000f)
    }
}
