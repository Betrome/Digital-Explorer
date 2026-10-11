package com.betrome.digitalexplorer

import android.content.Context
import android.graphics.RectF

/** Persisted calibration. Grid bounds are stored as screen fractions so they survive resolution changes. */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("digital_explorer", Context.MODE_PRIVATE)

    // Defaults taken from a 864x1920 screenshot of the mini-game.
    var left: Float get() = sp.getFloat("left", 0.0486f); set(v) = sp.edit().putFloat("left", v).apply()
    var top: Float get() = sp.getFloat("top", 0.3125f); set(v) = sp.edit().putFloat("top", v).apply()
    var right: Float get() = sp.getFloat("right", 0.935f); set(v) = sp.edit().putFloat("right", v).apply()
    var bottom: Float get() = sp.getFloat("bottom", 0.6458f); set(v) = sp.edit().putFloat("bottom", v).apply()
    var cols: Int get() = sp.getInt("cols", 5); set(v) = sp.edit().putInt("cols", v).apply()
    var rows: Int get() = sp.getInt("rows", 5); set(v) = sp.edit().putInt("rows", v).apply()
    var stepDelayMs: Int get() = sp.getInt("delay_ms", 700); set(v) = sp.edit().putInt("delay_ms", v).apply()

    fun gridRect(w: Int, h: Int) = RectF(left * w, top * h, right * w, bottom * h)
}
