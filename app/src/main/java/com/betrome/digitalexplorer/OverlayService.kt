package com.betrome.digitalexplorer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Floating control panel + the bot loop (capture -> analyse -> plan -> tap). */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var settings: Settings
    private lateinit var capturer: ScreenCapturer
    private lateinit var panel: View
    private lateinit var startBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private val botThread = HandlerThread("bot").apply { start() }
    private val bot = Handler(botThread.looper)
    private var extraView: View? = null

    @Volatile private var running = false
    private var lastHash = 0
    private var stuck = 0
    private val avoid = HashSet<Int>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || ::capturer.isInitialized) return START_NOT_STICKY
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(
            intent.getIntExtra("resultCode", 0),
            @Suppress("DEPRECATION") intent.getParcelableExtra("data")!!
        )
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = Settings(this)
        capturer = ScreenCapturer(this, projection)
        showPanel()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("de", "Digital Explorer", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "de")
            .setContentTitle("Digital Explorer running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    private fun overlayParams(touchable: Boolean, w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    private fun showPanel() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 20, 20, 40))
            setPadding(8, 8, 8, 8)
        }
        val handle = TextView(this).apply { text = "⋮⋮ drag"; setTextColor(Color.WHITE); textSize = 12f }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; textSize = 11f; minHeight = 0; minimumHeight = 0
            setOnClickListener { onClick() }
        }
        startBtn = btn("Start") { toggle() }
        col.addView(handle)
        col.addView(startBtn)
        col.addView(btn("Calibrate") { calibrate() })
        col.addView(btn("Preview") { preview() })
        col.addView(btn("Quit") { stopSelf() })

        val lp = overlayParams(true, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 120 }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(col, lp)
                }
            }
            true
        }
        panel = col
        wm.addView(col, lp)
    }

    private fun clearExtra() { extraView?.let { runCatching { wm.removeView(it) } }; extraView = null }

    private fun calibrate() {
        if (running) toggle()
        clearExtra()
        val v = CalibrationView(this, settings) { clearExtra(); toast("Calibration saved") }
        extraView = v
        wm.addView(v, overlayParams(true, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
    }

    private fun preview() {
        clearExtra()
        val bmp = capturer.grab() ?: return toast("No frame yet")
        val v = PreviewView(this, settings, GridAnalyzer.analyze(bmp, settings))
        extraView = v
        wm.addView(v, overlayParams(false, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
        ui.postDelayed({ if (extraView === v) clearExtra() }, 4000)
    }

    private fun toggle() {
        if (!running) {
            if (TapService.instance == null) return toast("Enable the accessibility service first")
            clearExtra()
            running = true; stuck = 0; avoid.clear(); lastHash = 0
            startBtn.text = "Stop"
            bot.post { tick() }
        } else {
            running = false
            startBtn.text = "Start"
        }
    }

    private fun tick() {
        if (!running) return
        val bmp = capturer.grab()
        val tapper = TapService.instance
        if (bmp != null && tapper != null) {
            val grid = GridAnalyzer.analyze(bmp, settings)
            val hash = grid.contentDeepHashCode()
            if (hash == lastHash) stuck++ else { stuck = 0; avoid.clear() }
            lastHash = hash
            val pc = settings.playerCol; val pr = settings.playerRow
            var step = Planner.next(grid, pc, pr, avoid)
            if (step != null && stuck >= 3) {
                // Same screen after several taps: give up on this target and try another.
                avoid.add((pr + step.dr) * settings.cols + (pc + step.dc))
                step = Planner.next(grid, pc, pr, avoid)
                stuck = 0
            }
            if (step != null) {
                val rect = settings.cellRect(pc + step.dc, pr + step.dr, bmp.width, bmp.height)
                tapper.tap(rect.centerX(), rect.top + rect.height() * 0.6f)
            }
        }
        bot.postDelayed({ tick() }, settings.stepDelayMs.toLong())
    }

    private fun toast(msg: String) = ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }.let { }

    override fun onDestroy() {
        running = false
        clearExtra()
        if (::panel.isInitialized) runCatching { wm.removeView(panel) }
        if (::capturer.isInitialized) capturer.release()
        botThread.quitSafely()
        super.onDestroy()
    }
}
