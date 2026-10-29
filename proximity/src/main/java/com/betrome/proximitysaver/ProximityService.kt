package com.betrome.proximitysaver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast

/**
 * Watches the proximity sensor and, once it has read "covered" continuously for the configured delay, takes
 * Android's PROXIMITY_SCREEN_OFF wake lock (the one the phone app uses during calls). That blanks the display while
 * the foreground app (Pokémon GO, Digimon Up...) keeps running. Uncovering releases it at once.
 * Short or spurious "near" blips never reach the system because the lock is only held after the delay.
 */
class ProximityService : Service(), SensorEventListener {
    private lateinit var settings: Settings
    private lateinit var sm: SensorManager
    private lateinit var lock: PowerManager.WakeLock
    private var sensor: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var near = false
    private val screenOff = Runnable { if (near && !lock.isHeld) lock.acquire() }

    // Only watch the sensor while the phone is awake; proximity blanking doesn't count as "screen off" here.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) listen() else unlisten()
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        sm = getSystemService(SensorManager::class.java)
        sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val pm = getSystemService(PowerManager::class.java)
        lock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "ProximitySaver:screen")
        lock.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must come first, even when we're about to stop, or Android kills the app.
        val ok = runCatching { startForeground(1, notification()) }.isSuccess
        val pm = getSystemService(PowerManager::class.java)
        if (intent?.action == ACTION_STOP) settings.enabled = false
        val problem = when {
            sensor == null -> "This phone has no proximity sensor"
            !pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) ->
                "This phone doesn't let apps turn the screen off by proximity"
            else -> null
        }
        if (problem != null) { toast(problem); settings.enabled = false }
        if (!ok || !settings.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            })
            if (pm.isInteractive) listen()
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ps", "Proximity Saver", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(this, 1, Intent(this, ProximityService::class.java).setAction(ACTION_STOP), flags)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        return Notification.Builder(this, "ps")
            .setContentTitle("Proximity Saver on")
            .setContentText("Cover the sensor to turn the screen off")
            .setSmallIcon(android.R.drawable.ic_lock_power_off)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun listen() {
        if (listening) return
        listening = true
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unlisten() {
        if (!listening) return
        listening = false
        sm.unregisterListener(this)
        setNear(false)
    }

    override fun onSensorChanged(e: SensorEvent) {
        setNear(e.values[0] < settings.thresholdCm(e.sensor.maximumRange))
    }

    private fun setNear(n: Boolean) {
        if (n == near) return
        near = n
        if (near) {
            handler.postDelayed(screenOff, settings.delayMs.toLong())
        } else {
            handler.removeCallbacks(screenOff)
            if (lock.isHeld) lock.release()
        }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    private fun toast(msg: String) = handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }.let { }

    override fun onDestroy() {
        if (running) {
            running = false
            unregisterReceiver(screenReceiver)
            unlisten()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.betrome.proximitysaver.STOP"
        @Volatile var running = false
            private set
    }
}
