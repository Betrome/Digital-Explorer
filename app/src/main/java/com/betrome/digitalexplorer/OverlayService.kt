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
    @Volatile private var labelView: LabelView? = null
    @Volatile private var loopActive = false
    private var lastHash = 0
    private var stuck = 0
    private var lastKey = 0
    // Last known Digimon cell, used if it can't be spotted in a frame (it normally sits in column 1).
    private var pCol = 1
    private var pRow = 0
    private val avoid = HashSet<Int>()
    private val blocked = HashSet<Int>()
    private var lastTapDest = -1

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
        col.addView(btn("Labels") { toggleLabels() })
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
        labelView?.let { labelView = null; runCatching { wm.removeView(it) } }
        clearExtra()
        val v = CalibrationView(this, settings) { clearExtra(); toast("Calibration saved") }
        extraView = v
        wm.addView(v, overlayParams(true, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
    }

    private fun toggleLabels() {
        val existing = labelView
        if (existing != null) {
            labelView = null
            runCatching { wm.removeView(existing) }
            return
        }
        val v = LabelView(this)
        wm.addView(v, overlayParams(false, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
        labelView = v
        ensureLoop()
    }

    private fun toggle() {
        if (!running) {
            if (TapService.instance == null) return toast("Enable the accessibility service first")
            clearExtra()
            running = true; stuck = 0; avoid.clear(); blocked.clear(); lastTapDest = -1; lastHash = 0; pCol = 1; pRow = settings.rows / 2
            startBtn.text = "Stop"
            ensureLoop()
        } else {
            running = false
            startBtn.text = "Start"
        }
    }

    private fun ensureLoop() {
        if (loopActive) return
        loopActive = true
        bot.post { loop() }
    }

    private fun loop() {
        if (!running && labelView == null) { loopActive = false; return }
        tick()
        bot.postDelayed({ loop() }, settings.stepDelayMs.toLong())
    }

    private fun tick() {
        // The label overlay would be captured with the screen, so hide it for the grab.
        val lv = labelView
        if (lv != null) { ui.post { lv.alpha = 0f }; Thread.sleep(90) }
        val bmp = capturer.grab()
        if (lv != null) ui.post { lv.alpha = 1f }
        val tapper = TapService.instance
        if (bmp == null) return
        val a = GridAnalyzer.analyze(bmp, settings, pCol, pRow)
        if (a.popup) {
            // "Cannot move to this location." - the last tap was refused, so never route through it.
            if (lastTapDest >= 0) { blocked.add(lastTapDest); lastTapDest = -1 }
            return
        }
        pCol = a.playerCol; pRow = a.playerRow
        val hash = a.grid.contentDeepHashCode()
        if (hash != lastHash) { avoid.clear(); blocked.clear() }
        lastHash = hash
        var plan = Planner.plan(a.grid, pCol, pRow, avoid, blocked)
        if (plan != null) {
            // Tapping a block/item next to us that changes nothing several times in a row: skip it.
            val dest = (pRow + plan.step.dr) * settings.cols + (pCol + plan.step.dc)
            val isTarget = a.grid[pRow + plan.step.dr][pCol + plan.step.dc].let { it == Cell.BLOCK || it == Cell.ITEM }
            val key = hash * 31 + dest * 7 + pCol * 3 + pRow
            stuck = if (running && isTarget && key == lastKey) stuck + 1 else 0
            lastKey = key
            if (stuck >= 5) {
                avoid.add(dest)
                stuck = 0
                plan = Planner.plan(a.grid, pCol, pRow, avoid, blocked)
            }
        }
        lv?.let { v -> val p = plan; ui.post { v.update(a.geometry, a.grid, pCol, pRow, p) } }
        if (running && tapper != null && plan != null) {
            val tc = pCol + plan.step.dc
            val tr = pRow + plan.step.dr
            val rect = a.geometry.cell(tc, tr)
            // Tap the middle of the tile; on the bottom row the wall hides the lower half, so aim a bit higher.
            val fy = if (tr == settings.rows - 1) 0.4f else 0.5f
            lastTapDest = tr * settings.cols + tc
            tapper.tap(rect.centerX(), rect.top + rect.height() * fy)
        }
    }

    private fun toast(msg: String) = ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }.let { }

    override fun onDestroy() {
        running = false
        labelView?.let { runCatching { wm.removeView(it) } }
        labelView = null
        clearExtra()
        if (::panel.isInitialized) runCatching { wm.removeView(panel) }
        if (::capturer.isInitialized) capturer.release()
        botThread.quitSafely()
        super.onDestroy()
    }
}
