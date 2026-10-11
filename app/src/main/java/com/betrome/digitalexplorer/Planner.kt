package com.betrome.digitalexplorer

/** One cardinal step: (dc, dr) with exactly one of them non-zero. */
data class Step(val dc: Int, val dr: Int)

object Planner {
    private val DIRS = arrayOf(Step(0, -1), Step(0, 1), Step(-1, 0), Step(1, 0))

    private const val VISITED_COST = 30

    /**
     * Sweeps column by column: the target is the cheapest-to-reach block/item in the left-most
     * column that still has one. Walking into a block destroys it, so blocks are valid targets.
     * Tiles already walked on (VISITED) cost [VISITED_COST], so routes avoid them unless there is
     * no other way. With nothing left on screen, steps right so the camera scrolls.
     * [avoid] holds cell indices (r * cols + c) that seem stuck.
     */
    fun next(grid: Array<Array<Cell>>, pc: Int, pr: Int, avoid: Set<Int>): Step? {
        val rows = grid.size
        val cols = grid[0].size
        val dist = Array(rows) { IntArray(cols) { Int.MAX_VALUE } }
        val first = Array(rows) { arrayOfNulls<Step>(cols) }
        val queue = java.util.PriorityQueue<IntArray>(compareBy { it[2] })
        dist[pr][pc] = 0
        queue.add(intArrayOf(pc, pr, 0))
        while (queue.isNotEmpty()) {
            val (c, r, d0) = queue.poll()
            if (d0 > dist[r][c]) continue
            for (d in DIRS) {
                val nc = c + d.dc
                val nr = r + d.dr
                if (nc !in 0 until cols || nr !in 0 until rows) continue
                val nd = d0 + if (grid[nr][nc] == Cell.VISITED) VISITED_COST else 1
                if (nd < dist[nr][nc]) {
                    dist[nr][nc] = nd
                    first[nr][nc] = first[r][c] ?: d
                    queue.add(intArrayOf(nc, nr, nd))
                }
            }
        }
        var best: IntArray? = null
        var bestScore = Int.MAX_VALUE
        for (r in 0 until rows) for (c in 0 until cols) {
            val cell = grid[r][c]
            if (cell != Cell.ITEM && cell != Cell.BLOCK) continue
            if ((r * cols + c) in avoid || dist[r][c] == Int.MAX_VALUE || (r == pr && c == pc)) continue
            val score = c * 10000 + dist[r][c] * 10 + (if (cell == Cell.ITEM) 0 else 1)
            if (score < bestScore) { bestScore = score; best = intArrayOf(c, r) }
        }
        best?.let { return first[it[1]][it[0]] }
        return if (pc + 1 < cols) Step(1, 0) else null
    }
}
