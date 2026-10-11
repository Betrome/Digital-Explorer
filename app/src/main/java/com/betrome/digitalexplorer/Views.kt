package com.betrome.digitalexplorer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

private fun paint(color: Int, stroke: Boolean = true, size: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = if (stroke) Paint.Style.STROKE else Paint.Style.FILL
    strokeWidth = 3f
    if (size > 0) textSize = size
}

/** Non-touchable overlay labelling what was recognised in each cell, plus the planned move. */
class LabelView(ctx: Context) : View(ctx) {
    private var geo: Geometry? = null
    private var grid: Array<Array<Cell>>? = null
    private var pc = 0
    private var pr = 0
    private var plan: Plan? = null
    private val line = paint(Color.argb(160, 255, 255, 255))
    private val text = paint(Color.WHITE, false, 40f).apply { isFakeBoldText = true }
    private val fill = paint(Color.WHITE, false)

    fun update(geometry: Geometry, g: Array<Array<Cell>>, playerCol: Int, playerRow: Int, p: Plan?) {
        geo = geometry; grid = g; pc = playerCol; pr = playerRow; plan = p
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val g = grid ?: return
        val geo = geo ?: return
        for (r in g.indices) for (c in g[r].indices) {
            val rect = geo.cell(c, r)
            canvas.drawRect(rect, line)
            val (label, color) = when (g[r][c]) {
                Cell.BLOCK -> "BLOCK" to Color.MAGENTA
                Cell.ITEM -> "ITEM" to Color.rgb(255, 165, 0)
                Cell.PLAYER -> "YOU" to Color.GREEN
                Cell.VISITED -> "visited" to Color.CYAN
                Cell.GROUND -> "" to Color.WHITE
            }
            if (label.isEmpty()) continue
            fill.color = Color.argb(170, 0, 0, 0)
            val w = text.measureText(label)
            canvas.drawRect(rect.centerX() - w / 2 - 6, rect.centerY() - 38f, rect.centerX() + w / 2 + 6, rect.centerY() + 12f, fill)
            text.color = color
            canvas.drawText(label, rect.centerX() - w / 2, rect.centerY(), text)
        }
        plan?.let { p ->
            p.target?.let { t ->
                val rt = geo.cell(t[0], t[1])
                canvas.drawRect(rt, paint(Color.RED).apply { strokeWidth = 8f })
            }
            val rp = geo.cell(pc, pr)
            val arrow = when {
                p.step.dc > 0 -> "→"; p.step.dc < 0 -> "←"; p.step.dr > 0 -> "↓"; else -> "↑"
            }
            text.color = Color.YELLOW
            canvas.drawText(arrow, rp.centerX() - 15f, rp.centerY() + 40f, text)
        }
    }
}

/** Full-screen calibration: drag over the whole block grid. */
class CalibrationView(ctx: Context, private val s: Settings, private val onDone: () -> Unit) : View(ctx) {
    private var x0 = 0f; private var y0 = 0f; private var x1 = 0f; private var y1 = 0f
    private val dim = paint(Color.argb(120, 0, 0, 0), false)
    private val line = paint(Color.CYAN)
    private val text = paint(Color.WHITE, false, 48f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; x1 = e.x; y1 = e.y }
            MotionEvent.ACTION_MOVE -> { x1 = e.x; y1 = e.y }
            MotionEvent.ACTION_UP -> {
                val l = minOf(x0, x1); val t = minOf(y0, y1); val rr = maxOf(x0, x1); val b = maxOf(y0, y1)
                if (rr - l > 100 && b - t > 100) {
                    s.left = l / width; s.right = rr / width; s.top = t / height; s.bottom = b / height
                    onDone()
                }
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawText("Drag over the whole block grid", 40f, 160f, text)
        canvas.drawRect(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), line)
    }
}
