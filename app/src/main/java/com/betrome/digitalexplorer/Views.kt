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

/** Non-touchable overlay showing the detected grid and what was recognised in each cell. */
class PreviewView(ctx: Context, private val s: Settings, private val grid: Array<Array<Cell>>) : View(ctx) {
    private val line = paint(Color.CYAN)
    private val text = paint(Color.WHITE, false, 44f)
    override fun onDraw(canvas: Canvas) {
        for (r in grid.indices) for (c in grid[r].indices) {
            val rect = s.cellRect(c, r, width, height)
            canvas.drawRect(rect, line)
            val (label, color) = when (grid[r][c]) {
                Cell.BLOCK -> "B" to Color.MAGENTA
                Cell.ITEM -> "I" to Color.rgb(255, 165, 0)
                Cell.PLAYER -> "P" to Color.GREEN
                Cell.GROUND -> "" to Color.WHITE
            }
            text.color = color
            canvas.drawText(label, rect.centerX() - 12f, rect.centerY() + 15f, text)
        }
    }
}

/** Full-screen calibration: drag over the grid, then tap the cell the Digimon stands in. */
class CalibrationView(ctx: Context, private val s: Settings, private val onDone: () -> Unit) : View(ctx) {
    private var step = 0
    private var x0 = 0f; private var y0 = 0f; private var x1 = 0f; private var y1 = 0f
    private val dim = paint(Color.argb(120, 0, 0, 0), false)
    private val line = paint(Color.CYAN)
    private val text = paint(Color.WHITE, false, 48f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (step == 0) {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; x1 = e.x; y1 = e.y }
                MotionEvent.ACTION_MOVE -> { x1 = e.x; y1 = e.y }
                MotionEvent.ACTION_UP -> {
                    val l = minOf(x0, x1); val t = minOf(y0, y1); val rr = maxOf(x0, x1); val b = maxOf(y0, y1)
                    if (rr - l > 100 && b - t > 100) {
                        s.left = l / width; s.right = rr / width; s.top = t / height; s.bottom = b / height
                        step = 1
                    }
                }
            }
        } else if (e.action == MotionEvent.ACTION_UP) {
            val g = RectF(s.left * width, s.top * height, s.right * width, s.bottom * height)
            s.playerCol = ((e.x - g.left) / (g.width() / s.cols)).toInt().coerceIn(0, s.cols - 1)
            s.playerRow = ((e.y - g.top) / (g.height() / s.rows)).toInt().coerceIn(0, s.rows - 1)
            onDone()
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        if (step == 0) {
            canvas.drawText("Drag over the whole block grid", 40f, 160f, text)
            canvas.drawRect(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), line)
        } else {
            canvas.drawText("Tap the cell your Digimon is in", 40f, 160f, text)
            val g = RectF(s.left * width, s.top * height, s.right * width, s.bottom * height)
            val cw = g.width() / s.cols; val ch = g.height() / s.rows
            for (c in 0 until s.cols) for (r in 0 until s.rows)
                canvas.drawRect(g.left + c * cw, g.top + r * ch, g.left + (c + 1) * cw, g.top + (r + 1) * ch, line)
        }
    }
}
