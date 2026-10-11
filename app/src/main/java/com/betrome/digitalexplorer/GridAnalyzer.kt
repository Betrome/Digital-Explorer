package com.betrome.digitalexplorer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

/** VISITED is the bright-blue floor the Digimon has already walked on. ITEM is anything else non-blank. */
enum class Cell { GROUND, VISITED, BLOCK, ITEM, PLAYER }

/** [playerCol]/[playerRow] are the Digimon's cell (last known cell if it could not be seen this frame). */
class Analysis(val grid: Array<Array<Cell>>, val playerCol: Int, val playerRow: Int)

/**
 * Classifies each grid cell by colour, so any kind of item is picked up, not just one colour:
 *  - floor: blue/teal (dark = untouched, bright = visited);
 *  - BLOCK: purple-grey pyramid, seen at the lower-middle of the cell (its apex bleeds upward);
 *  - ITEM: anything in the middle of the cell that is neither floor nor pyramid.
 * The Digimon is located from its deep-red cape and its sprite is masked out of every cell, since
 * the sprite overlaps neighbouring cells and would otherwise look like an item.
 */
object GridAnalyzer {
    private const val ITEM_MIN = 0.10f
    private const val BLOCK_MIN = 0.15f
    private const val VISITED_MIN = 0.20f
    private const val PLAYER_MIN_FRACTION = 0.04f // of a cell's area, in red pixels

    private class Player(val col: Int, val row: Int, val box: RectF)

    private fun isFloor(h: Float, s: Float, v: Float) = h in 185f..225f && s > 0.5f && v > 0.2f
    private fun isPurple(h: Float, s: Float, v: Float) = h in 225f..300f && s in 0.1f..0.6f && v > 0.25f

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

        val player = findPlayer(px, gw, gh, cw, ch, s)
        val pc = player?.col ?: lastCol
        val pr = player?.row ?: lastRow
        val box = player?.box
        val hsv = FloatArray(3)
        val grid = Array(s.rows) { r ->
            Array(s.cols) { c ->
                if (c == pc && r == pr) return@Array Cell.PLAYER
                val x0 = (c * cw + cw * 0.20f).toInt()
                val x1 = ((c * cw + cw * 0.80f).toInt()).coerceAtMost(gw)
                val y0 = (r * ch + ch * 0.20f).toInt()
                val y1 = ((r * ch + ch * 0.95f).toInt()).coerceAtMost(gh)
                val midY0 = (r * ch + ch * 0.20f).toInt()
                val midY1 = (r * ch + ch * 0.80f).toInt()
                val baseY0 = (r * ch + ch * 0.45f).toInt()
                var n = 0; var items = 0; var blocks = 0; var bright = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        if (box == null || !box.contains(x.toFloat(), y.toFloat())) {
                            Color.colorToHSV(px[y * gw + x], hsv)
                            val hue = hsv[0]; val sat = hsv[1]; val v = hsv[2]
                            if (y in midY0 until midY1 && v > 0.25f && !isFloor(hue, sat, v) && !isPurple(hue, sat, v)) items++
                            if (y >= baseY0 && isPurple(hue, sat, v)) blocks++
                            if (hue in 180f..215f && sat > 0.6f && v > 0.7f) bright++
                        }
                        n++
                        x += 3
                    }
                    y += 3
                }
                when {
                    n == 0 -> Cell.GROUND
                    items.toFloat() / n > ITEM_MIN -> Cell.ITEM
                    blocks.toFloat() / n > BLOCK_MIN -> Cell.BLOCK
                    bright.toFloat() / n > VISITED_MIN -> Cell.VISITED
                    else -> Cell.GROUND
                }
            }
        }
        return Analysis(grid, pc, pr)
    }

    /** Finds the Digimon via its red cape; null if not visible. */
    private fun findPlayer(px: IntArray, gw: Int, gh: Int, cw: Float, ch: Float, s: Settings): Player? {
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
        val sortedY = fy.sorted()
        val bottom = sortedY[(sortedY.size * 0.95f).toInt().coerceAtMost(sortedY.size - 1)]
        val box = RectF(
            fx.min() - cw * 0.12f, fy.min() - ch * 0.05f,
            fx.max() + cw * 0.12f, fy.max() + ch * 0.15f
        )
        return Player(
            (cx / cw).toInt().coerceIn(0, s.cols - 1),
            (bottom / ch).toInt().coerceIn(0, s.rows - 1),
            box
        )
    }
}
