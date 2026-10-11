package com.betrome.digitalexplorer

import android.graphics.Bitmap
import android.graphics.Color

enum class Cell { GROUND, BLOCK, ITEM, PLAYER }

/**
 * Classifies each grid cell by colour. Blocks are tall purple-grey pyramids whose base sits at the
 * bottom of the cell, so only the lower-middle part of a cell is sampled (the apex bleeds into the
 * cell above). Items are orange chips. Everything else (dark or bright blue floor) is ground.
 */
object GridAnalyzer {
    private const val ITEM_MIN = 0.10f
    private const val BLOCK_MIN = 0.15f

    fun analyze(bmp: Bitmap, s: Settings): Array<Array<Cell>> {
        val w = bmp.width
        val h = bmp.height
        val hsv = FloatArray(3)
        return Array(s.rows) { r ->
            Array(s.cols) { c ->
                if (c == s.playerCol && r == s.playerRow) return@Array Cell.PLAYER
                val rect = s.cellRect(c, r, w, h)
                val x0 = (rect.left + rect.width() * 0.25f).toInt()
                val x1 = (rect.left + rect.width() * 0.75f).toInt().coerceAtMost(w - 1)
                val y0 = (rect.top + rect.height() * 0.45f).toInt()
                val y1 = (rect.top + rect.height() * 0.95f).toInt().coerceAtMost(h - 1)
                var n = 0; var items = 0; var blocks = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        Color.colorToHSV(bmp.getPixel(x, y), hsv)
                        val hue = hsv[0]; val sat = hsv[1]; val v = hsv[2]
                        if (hue in 11f..50f && sat > 0.6f && v > 0.6f) items++
                        else if (hue in 235f..290f && sat in 0.12f..0.55f && v > 0.3f) blocks++
                        n++
                        x += 3
                    }
                    y += 3
                }
                if (n == 0) Cell.GROUND
                else if (items.toFloat() / n > ITEM_MIN) Cell.ITEM
                else if (blocks.toFloat() / n > BLOCK_MIN) Cell.BLOCK
                else Cell.GROUND
            }
        }
    }
}
