package com.betrome.digitalexplorer

import android.graphics.Bitmap
import android.graphics.Color

enum class Cell { GROUND, BLOCK, ITEM, PLAYER }

/** [playerCol]/[playerRow] are the Digimon's cell (last known cell if it could not be seen this frame). */
class Analysis(val grid: Array<Array<Cell>>, val playerCol: Int, val playerRow: Int)

/**
 * Classifies each grid cell by colour. Blocks are tall purple-grey pyramids whose base sits at the
 * bottom of the cell, so only the lower-middle part of a cell is sampled (the apex bleeds into the
 * cell above). Items are orange chips. Everything else (dark or bright blue floor) is ground.
 * The Digimon is located from its deep-red cape.
 */
object GridAnalyzer {
    private const val ITEM_MIN = 0.10f
    private const val BLOCK_MIN = 0.15f
    private const val PLAYER_MIN_FRACTION = 0.04f // of a cell's area, in red pixels

    fun analyze(bmp: Bitmap, s: Settings, lastCol: Int, lastRow: Int): Analysis {
        val g = s.gridRect(bmp.width, bmp.height)
        val gx = g.left.toInt()
        val gy = g.top.toInt()
        val gw = g.width().toInt().coerceAtMost(bmp.width - gx)
        val gh = g.height().toInt().coerceAtMost(bmp.height - gy)
        val px = IntArray(gw * gh)
        bmp.getPixels(px, 0, gw, gx, gy, gw, gh)
        val cw = g.width() / s.cols
        val ch = g.height() / s.rows

        val (pc, pr) = findPlayer(px, gw, gh, cw, ch, s) ?: (lastCol to lastRow)
        val hsv = FloatArray(3)
        val grid = Array(s.rows) { r ->
            Array(s.cols) { c ->
                if (c == pc && r == pr) return@Array Cell.PLAYER
                val x0 = (c * cw + cw * 0.25f).toInt()
                val x1 = ((c * cw + cw * 0.75f).toInt()).coerceAtMost(gw)
                val y0 = (r * ch + ch * 0.45f).toInt()
                val y1 = ((r * ch + ch * 0.95f).toInt()).coerceAtMost(gh)
                var n = 0; var items = 0; var blocks = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        Color.colorToHSV(px[y * gw + x], hsv)
                        val hue = hsv[0]; val sat = hsv[1]; val v = hsv[2]
                        if (hue in 11f..36f && sat > 0.6f && v > 0.6f) items++
                        else if (hue in 235f..290f && sat in 0.12f..0.55f && v > 0.3f) blocks++
                        n++
                        x += 3
                    }
                    y += 3
                }
                when {
                    n == 0 -> Cell.GROUND
                    items.toFloat() / n > ITEM_MIN -> Cell.ITEM
                    blocks.toFloat() / n > BLOCK_MIN -> Cell.BLOCK
                    else -> Cell.GROUND
                }
            }
        }
        return Analysis(grid, pc, pr)
    }

    /** Finds the Digimon via its red cape; (col, row) or null if not visible. */
    private fun findPlayer(px: IntArray, gw: Int, gh: Int, cw: Float, ch: Float, s: Settings): Pair<Int, Int>? {
        val hsv = FloatArray(3)
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        var y = 0
        while (y < gh) {
            var x = 0
            while (x < gw) {
                Color.colorToHSV(px[y * gw + x], hsv)
                if ((hsv[0] < 8f || hsv[0] > 350f) && hsv[1] > 0.6f && hsv[2] > 0.35f) { xs.add(x); ys.add(y) }
                x += 2
            }
            y += 2
        }
        if (xs.size < PLAYER_MIN_FRACTION * cw * ch / 4f) return null
        // Median first (robust to a few red-edged items), then keep only pixels near the sprite.
        val medX = xs.sorted()[xs.size / 2]
        val medY = ys.sorted()[ys.size / 2]
        val fx = ArrayList<Int>()
        val fy = ArrayList<Int>()
        for (i in xs.indices) {
            if (kotlin.math.abs(xs[i] - medX) <= cw * 0.9f && ys[i] > medY - ch && ys[i] < medY + ch * 0.8f) {
                fx.add(xs[i]); fy.add(ys[i])
            }
        }
        if (fx.isEmpty()) return null
        val cx = fx.average()
        val bottom = fy.sorted()[(fy.size * 0.95f).toInt().coerceAtMost(fy.size - 1)]
        val col = (cx / cw).toInt().coerceIn(0, s.cols - 1)
        val row = (bottom / ch).toInt().coerceIn(0, s.rows - 1)
        return col to row
    }
}
