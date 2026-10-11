package com.betrome.digitalexplorer

/** One cardinal step: (dc, dr) with exactly one of them non-zero. */
data class Step(val dc: Int, val dr: Int)

object Planner {
    private val DIRS = arrayOf(Step(1, 0), Step(0, -1), Step(0, 1), Step(-1, 0))

    /**
     * BFS over the visible grid (4-neighbourhood) to the nearest block or item, preferring items and
     * cells further forward (higher column) on ties. Walking into a block destroys it, so blocks
     * are treated as passable targets. With nothing left on screen, steps forward (right) so the
     * camera scrolls and reveals more. [avoid] holds cell indices (r * cols + c) that seem stuck.
     */
    fun next(grid: Array<Array<Cell>>, pc: Int, pr: Int, avoid: Set<Int>): Step? {
        val rows = grid.size
        val cols = grid[0].size
        val dist = Array(rows) { IntArray(cols) { -1 } }
        val first = Array(rows) { arrayOfNulls<Step>(cols) }
        val queue = ArrayDeque<IntArray>()
        dist[pr][pc] = 0
        queue.add(intArrayOf(pc, pr))
        var best: IntArray? = null
        var bestScore = Int.MAX_VALUE
        while (queue.isNotEmpty()) {
            val (c, r) = queue.removeFirst()
            val cell = grid[r][c]
            if (dist[r][c] > 0 && (cell == Cell.ITEM || cell == Cell.BLOCK) && (r * cols + c) !in avoid) {
                val score = dist[r][c] * 100 - c * 10 - (if (cell == Cell.ITEM) 5 else 0)
                if (score < bestScore) { bestScore = score; best = intArrayOf(c, r) }
            }
            for (d in DIRS) {
                val nc = c + d.dc
                val nr = r + d.dr
                if (nc !in 0 until cols || nr !in 0 until rows || dist[nr][nc] >= 0) continue
                dist[nr][nc] = dist[r][c] + 1
                first[nr][nc] = first[r][c] ?: d
                queue.add(intArrayOf(nc, nr))
            }
        }
        best?.let { return first[it[1]][it[0]] }
        return if (pc + 1 < cols) Step(1, 0) else null
    }
}
