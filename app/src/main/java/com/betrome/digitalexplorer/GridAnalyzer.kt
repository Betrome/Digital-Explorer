package com.betrome.digitalexplorer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

/** VISITED is the bright-blue floor the Digimon has already walked on. ITEM is anything else non-blank. */
enum class Cell { GROUND, VISITED, BLOCK, ITEM, PLAYER }

/** Pixel position of the tile grid: top-left corner of cell (0, 0) and the size of one cell. */
class Geometry(val left: Float, val top: Float, val cw: Float, val ch: Float, val cols: Int, val rows: Int) {
    fun cell(c: Int, r: Int) = RectF(left + c * cw, top + r * ch, left + (c + 1) * cw, top + (r + 1) * ch)
}

/** [playerCol]/[playerRow] are the Digimon's cell (last known cell if it could not be seen this frame). */
class Analysis(
    val grid: Array<Array<Cell>>,
    val playerCol: Int,
    val playerRow: Int,
    val geometry: Geometry,
    val popup: Boolean = false
)

/**
 * Reads the board from a screenshot:
 *  - Grid: the game's own tile lines (thin bright lines between tiles) are located in the frame and
 *    fitted to a regular grid, so the saved calibration only needs to be roughly right.
 *  - Cells: floor is blue/teal (dark = untouched, bright = visited); BLOCK is a purple-grey pyramid
 *    seen at the lower-middle of the cell; ITEM is anything in the middle of the cell that is neither
 *    floor nor pyramid, so every kind of item is picked up.
 *  - Digimon: located from its deep-red cape and masked out of every cell, since the sprite
 *    overlaps neighbouring cells and would otherwise look like an item.
 */
object GridAnalyzer {
    private const val ITEM_MIN = 0.10f
    private const val BLOCK_MIN = 0.15f
    private const val VISITED_MIN = 0.20f
    private const val POPUP_MIN = 0.025f // fraction of the region that is the pop-up's bright cyan bar
    private const val PLAYER_MIN_FRACTION = 0.04f // of a cell's area, in red pixels
    private const val LINE_RATIO_MIN = 3f // best line fit vs. average fit, else keep the calibration

    // Tile size relative to screen width in this game's layout (measured from screenshots).
    private const val CELL_W_FRAC = 0.1790f
    private const val CELL_H_FRAC = 0.1466f

    private class Region(val px: IntArray, val ox: Int, val oy: Int, val w: Int, val h: Int) {
        fun at(x: Int, y: Int) = px[(y - oy) * w + (x - ox)]
        fun has(x: Int, y: Int) = x >= ox && y >= oy && x < ox + w && y < oy + h
    }

    private class Player(val col: Int, val row: Int, val box: RectF)

    private fun isFloor(h: Float, s: Float, v: Float) = h in 185f..225f && s > 0.5f && v > 0.2f
    private fun isPurple(h: Float, s: Float, v: Float) = h in 225f..300f && s in 0.1f..0.6f && v > 0.25f
    private fun lum(c: Int) = (c shr 16 and 255) + (c shr 8 and 255) + (c and 255)

    fun analyze(bmp: Bitmap, s: Settings, lastCol: Int, lastRow: Int): Analysis {
        val w = bmp.width
        val h = bmp.height
        val cwExp = w * CELL_W_FRAC
        val chExp = w * CELL_H_FRAC
        val g = s.gridRect(w, h)
        val rx0 = (g.left - cwExp * 0.7f).toInt().coerceAtLeast(0)
        val ry0 = (g.top - chExp * 0.7f).toInt().coerceAtLeast(0)
        val rx1 = (g.right + cwExp * 0.7f).toInt().coerceAtMost(w)
        val ry1 = (g.bottom + chExp * 0.7f).toInt().coerceAtMost(h)
        val reg = Region(IntArray((rx1 - rx0) * (ry1 - ry0)), rx0, ry0, rx1 - rx0, ry1 - ry0)
        bmp.getPixels(reg.px, 0, reg.w, rx0, ry0, reg.w, reg.h)

        val geo = fitGeometry(reg, s, g, cwExp, chExp)
        if (hasPopup(reg)) return Analysis(emptyArray(), lastCol, lastRow, geo, popup = true)

        val player = findPlayer(reg, geo)
        val pc = player?.col ?: lastCol
        val pr = player?.row ?: lastRow
        val box = player?.box
        val hsv = FloatArray(3)
        val grid = Array(s.rows) { r ->
            Array(s.cols) { c ->
                if (c == pc && r == pr) return@Array Cell.PLAYER
                val rect = geo.cell(c, r)
                val x0 = (rect.left + geo.cw * 0.20f).toInt()
                val x1 = (rect.left + geo.cw * 0.80f).toInt()
                val y0 = (rect.top + geo.ch * 0.20f).toInt()
                val y1 = (rect.top + geo.ch * 0.95f).toInt()
                val midY1 = (rect.top + geo.ch * 0.80f).toInt()
                val baseY0 = (rect.top + geo.ch * 0.45f).toInt()
                var n = 0; var items = 0; var blocks = 0; var bright = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        if (reg.has(x, y) && (box == null || !box.contains(x.toFloat(), y.toFloat()))) {
                            Color.colorToHSV(reg.at(x, y), hsv)
                            val hue = hsv[0]; val sat = hsv[1]; val v = hsv[2]
                            if (y < midY1 && v > 0.25f && !isFloor(hue, sat, v) && !isPurple(hue, sat, v)) items++
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
        return Analysis(grid, pc, pr, geo)
    }

    /**
     * Snaps the grid to the game's tile lines. Thin bright lines are picked out with a ridge filter,
     * the median over the line's length ignores items and pyramids, and a regular spacing is fitted
     * around the calibrated position. Falls back to the calibration for an axis with no clear fit.
     */
    private fun fitGeometry(reg: Region, s: Settings, g: RectF, cwExp: Float, chExp: Float): Geometry {
        val baseCw = g.width() / s.cols
        val baseCh = g.height() / s.rows
        val gx0 = g.left.toInt().coerceAtLeast(reg.ox)
        val gx1 = g.right.toInt().coerceAtMost(reg.ox + reg.w)
        val gy0 = g.top.toInt().coerceAtLeast(reg.oy)
        val gy1 = g.bottom.toInt().coerceAtMost(reg.oy + reg.h)
        var left = g.left; var top = g.top; var cw = baseCw; var ch = baseCh

        // Row lines: y of lines k = 0 until rows (the bottom edge is hidden behind the wall).
        val rowP = FloatArray(maxOf(reg.h - 6, 0))
        val buf = IntArray(maxOf(gx1 - gx0, gy1 - gy0) / 3 + 2)
        for (i in rowP.indices) {
            val y = reg.oy + 3 + i
            var k = 0
            var x = gx0
            while (x < gx1) {
                val v = lum(reg.at(x, y)) - (lum(reg.at(x, y - 3)) + lum(reg.at(x, y + 3))) / 2
                buf[k++] = if (v > 0) v else 0
                x += 3
            }
            java.util.Arrays.sort(buf, 0, k)
            rowP[i] = if (k > 0) buf[k / 2].toFloat() else 0f
        }
        // The floor's top edge (where the wall above it ends) pins which line is row 0, so a
        // calibration that includes the wall cannot shift the whole grid up by a row.
        val topAnchor = floorTop(reg, gx0, gx1, g.top)
        fit(rowP, reg.oy + 3, topAnchor ?: g.top, chExp, s.rows, if (topAnchor != null) 0.25f else 0.6f)
            ?.let { top = it.first; ch = it.second }

        // Column lines: interior lines only, k = 1 until cols (the outer edges are the view's frame).
        if (s.cols > 1) {
            val colP = FloatArray(maxOf(reg.w - 6, 0))
            for (i in colP.indices) {
                val x = reg.ox + 3 + i
                var k = 0
                var y = gy0
                while (y < gy1) {
                    val v = lum(reg.at(x, y)) - (lum(reg.at(x - 3, y)) + lum(reg.at(x + 3, y))) / 2
                    buf[k++] = if (v > 0) v else 0
                    y += 3
                }
                java.util.Arrays.sort(buf, 0, k)
                colP[i] = if (k > 0) buf[k / 2].toFloat() else 0f
            }
            val leftAnchor = floorLeft(reg, top, chExp * s.rows, g.left)
            // Column 0 is ~6% cut off by the view's left edge, so line 1 sits ~0.935 cells in from it.
            val line1 = if (leftAnchor != null) leftAnchor + 0.935f * cwExp else g.left + baseCw
            fit(colP, reg.ox + 3, line1, cwExp, s.cols - 1, if (leftAnchor != null) 0.25f else 0.6f)
                ?.let { left = it.first - it.second; cw = it.second }
        }
        return Geometry(left, top, cw, ch, s.cols, s.rows)
    }


    private fun isStrictFloor(c: Int, hsv: FloatArray): Boolean {
        Color.colorToHSV(c, hsv)
        return hsv[0] in 185f..225f && hsv[1] > 0.88f && hsv[2] > 0.2f
    }

    /**
     * Edge candidates in a floor-coverage profile: index i where floor suddenly starts (nothing
     * before it, floor after). Returns the one closest to [target], in absolute coordinates.
     */
    private fun edgeNear(frac: FloatArray, start: Int, target: Float): Float? {
        val m = 8
        val k = 4
        var best: Int? = null
        for (i in m until frac.size - k) {
            var after = 0f
            for (j in 0 until k) after += frac[i + j]
            var before = 0f
            for (j in 1..m) before += frac[i - j]
            if (after / k > 0.3f && before / m < 0.05f) {
                if (best == null || Math.abs(start + i - target) < Math.abs(best - target)) best = start + i
            }
        }
        return best?.toFloat()
    }

    /** y where the blue floor begins (the wall's base), closest to the calibrated top. */
    private fun floorTop(reg: Region, gx0: Int, gx1: Int, calTop: Float): Float? {
        val hsv = FloatArray(3)
        val frac = FloatArray(reg.h)
        for (i in 0 until reg.h) {
            var n = 0; var f = 0
            var x = gx0
            while (x < gx1) {
                if (isStrictFloor(reg.at(x, reg.oy + i), hsv)) f++
                n++
                x += 4
            }
            frac[i] = if (n > 0) f.toFloat() / n else 0f
        }
        return edgeNear(frac, reg.oy, calTop)
    }

    /** x where the blue floor begins (the view's left edge), closest to the calibrated left. */
    private fun floorLeft(reg: Region, top: Float, height: Float, calLeft: Float): Float? {
        val hsv = FloatArray(3)
        val y0 = top.toInt().coerceAtLeast(reg.oy)
        val y1 = (top + height).toInt().coerceAtMost(reg.oy + reg.h)
        val frac = FloatArray(reg.w)
        for (i in 0 until reg.w) {
            var n = 0; var f = 0
            var y = y0
            while (y < y1) {
                if (isStrictFloor(reg.at(reg.ox + i, y), hsv)) f++
                n++
                y += 4
            }
            frac[i] = if (n > 0) f.toFloat() / n else 0f
        }
        return edgeNear(frac, reg.ox, calLeft)
    }

    /** Best (first line position, spacing) for [count] evenly spaced lines, or null if unconvincing. */
    private fun fit(profile: FloatArray, start: Int, pos0: Float, period: Float, count: Int, window: Float): Pair<Float, Float>? {
        var best = -1f; var bestPos = pos0; var bestP = period
        var sum = 0f; var n = 0
        var pf = 0.94f
        while (pf <= 1.0601f) {
            val p = period * pf
            var d = -period * window
            while (d <= period * window) {
                val o = pos0 + d
                var sc = 0f
                var ok = true
                for (k in 0 until count) {
                    val i = Math.round(o + k * p) - start
                    if (i < 1 || i >= profile.size - 1) { ok = false; break }
                    sc += maxOf(profile[i - 1], profile[i], profile[i + 1])
                }
                if (ok) {
                    sum += sc; n++
                    if (sc > best) { best = sc; bestPos = o; bestP = p }
                }
                d += 1f
            }
            pf += 0.01f
        }
        if (n == 0 || best <= 0f || best / (sum / n) < LINE_RATIO_MIN) return null
        return bestPos to bestP
    }

    /**
     * The "Cannot move to this location." banner covers the middle of the grid; its bright cyan
     * underline (hue ~188, saturated, bright) is far more common there than anywhere else.
     */
    private fun hasPopup(reg: Region): Boolean {
        val hsv = FloatArray(3)
        var n = 0
        var cyan = 0
        var i = 0
        while (i < reg.px.size) {
            Color.colorToHSV(reg.px[i], hsv)
            if (hsv[0] in 183f..196f && hsv[1] > 0.65f && hsv[2] > 0.8f) cyan++
            n++
            i += 5
        }
        return n > 0 && cyan.toFloat() / n > POPUP_MIN
    }

    /** Finds the Digimon via its red cape; null if not visible. */
    private fun findPlayer(reg: Region, geo: Geometry): Player? {
        val hsv = FloatArray(3)
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        val x0 = maxOf(reg.ox, geo.left.toInt())
        val x1 = minOf(reg.ox + reg.w, (geo.left + geo.cols * geo.cw).toInt())
        val y0 = maxOf(reg.oy, geo.top.toInt())
        val y1 = minOf(reg.oy + reg.h, (geo.top + geo.rows * geo.ch).toInt())
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                Color.colorToHSV(reg.at(x, y), hsv)
                if ((hsv[0] < 8f || hsv[0] > 350f) && hsv[1] > 0.6f && hsv[2] > 0.35f) { xs.add(x); ys.add(y) }
                x += 2
            }
            y += 2
        }
        if (xs.size < PLAYER_MIN_FRACTION * geo.cw * geo.ch / 4f) return null
        // Median first (robust to a few red-edged items), then keep only pixels near the sprite.
        val medX = xs.sorted()[xs.size / 2]
        val medY = ys.sorted()[ys.size / 2]
        val fx = ArrayList<Int>()
        val fy = ArrayList<Int>()
        for (i in xs.indices) {
            if (kotlin.math.abs(xs[i] - medX) <= geo.cw * 0.9f && ys[i] > medY - geo.ch && ys[i] < medY + geo.ch * 0.8f) {
                fx.add(xs[i]); fy.add(ys[i])
            }
        }
        if (fx.isEmpty()) return null
        val cx = fx.average().toFloat()
        val sortedY = fy.sorted()
        val bottom = sortedY[(sortedY.size * 0.95f).toInt().coerceAtMost(sortedY.size - 1)]
        val box = RectF(
            fx.min() - geo.cw * 0.12f, fy.min() - geo.ch * 0.05f,
            fx.max() + geo.cw * 0.12f, fy.max() + geo.ch * 0.15f
        )
        // The cape sits ~0.2 cells above/left of the cell centre; shift so the reading is centred.
        return Player(
            ((cx - geo.left + geo.cw * 0.22f) / geo.cw).toInt().coerceIn(0, geo.cols - 1),
            ((bottom - geo.top - geo.ch * 0.19f) / geo.ch).toInt().coerceIn(0, geo.rows - 1),
            box
        )
    }
}
