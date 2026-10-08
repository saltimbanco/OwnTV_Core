package tv.own.owntv.core.epg

import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * Collapses the same programme listed more than once on one channel.
 *
 * Guide rows are keyed by `epgChannelId`, and that key is deliberately looked up across **every**
 * playlist and EPG feed rather than only the channel's own source — one playlist's `xmltv.php`
 * routinely covers another's lineup, and narrowing the query loses rows that are really there. The
 * cost of that breadth is this: when two feeds both carry a channel, every programme comes back
 * twice. The table's unique index is `(sourceId, epgChannelId, startMs)`, so it cannot stop them —
 * the two rows have different `sourceId`s, and usually different start times as well, because two
 * feeds rarely agree to the minute.
 *
 * On screen that looked like a guide drawing a block on top of another block (`22:04–00:16` under
 * `22:10–00:15`, the same film), and a Next/Later list that named the same programme twice in a row.
 *
 * **The test is a shared title and overlapping time, and both halves matter.** Overlapping alone is
 * two feeds disagreeing about a schedule, which is not something this can honestly resolve by
 * throwing half of it away; the same title back to back without overlap is a genuine repeat, which
 * channels really do broadcast. Only both together mean one programme written down twice.
 */
object EpgDedupe {

    /**
     * [rows] for a single channel with repeats removed, in start order.
     *
     * The **longest** of a duplicate set is kept. Where two feeds disagree about the edges, the wider
     * span is the one that covers the whole programme, so a block drawn from it leaves no gap either
     * side; the shorter one would leave the guide with holes it cannot explain.
     */
    fun collapse(rows: List<EpgProgrammeEntity>): List<EpgProgrammeEntity> {
        if (rows.size < 2) return rows
        // The DAO already serves these in start order; skip the re-sort when it did. The check is
        // one pass of Long comparisons, the sort it avoids is O(n log n) with full comparisons.
        val sorted = if (isStartOrdered(rows)) rows else rows.sortedWith(compareBy({ it.startMs }, { it.stopMs }))
        // Normalised once per row up front. The pairwise test used to normalise both titles on every
        // comparison, so a 500-row channel paid ~1000 trims, lowercases and regex passes for what is
        // 500 strings.
        val keys = ArrayList<String>(sorted.size)
        sorted.mapTo(keys) { normalise(it.title) }
        val kept = ArrayList<EpgProgrammeEntity>(sorted.size)
        val keptKeys = ArrayList<String>(sorted.size)
        for (i in sorted.indices) {
            val row = sorted[i]
            val previous = kept.lastOrNull()
            if (previous != null && overlaps(previous, row) && keptKeys.last() == keys[i]) {
                // Same programme, written twice. Keep whichever spans more of it.
                if (row.durationMs > previous.durationMs) kept[kept.lastIndex] = row
                continue
            }
            kept += row
            keptKeys += keys[i]
        }
        return kept
    }

    /** Start-then-stop order, the same order [collapse] sorts into. */
    private fun isStartOrdered(rows: List<EpgProgrammeEntity>): Boolean {
        for (i in 1 until rows.size) {
            val p = rows[i - 1]
            val c = rows[i]
            if (c.startMs < p.startMs || (c.startMs == p.startMs && c.stopMs < p.stopMs)) return false
        }
        return true
    }

    /** Overlapping time: one half of the duplicate test, the title match is the other. */
    private fun overlaps(a: EpgProgrammeEntity, b: EpgProgrammeEntity): Boolean =
        a.startMs < b.stopMs && b.startMs < a.stopMs

    /** The same broadcast written down twice: one title, one stretch of time. */
    private fun isSameProgramme(a: EpgProgrammeEntity, b: EpgProgrammeEntity): Boolean =
        overlaps(a, b) && normalise(a.title) == normalise(b.title)

    /**
     * Titles as two feeds write them: different case, padding, and runs of whitespace where one has
     * wrapped a line. Nothing cleverer — a feed that renames a programme is not the same listing, and
     * guessing that it is would merge two real programmes into one.
     */
    private fun normalise(title: String): String =
        title.trim().lowercase().replace(WHITESPACE, " ")

    private val EpgProgrammeEntity.durationMs: Long get() = (stopMs - startMs).coerceAtLeast(0L)

    private val WHITESPACE = Regex("\\s+")
}
