package komascroll.insights

import java.time.LocalDate

object ReadingStreaks {

    /** Length of the longest run of consecutive days in [days]. */
    fun longest(days: Collection<LocalDate>): Int {
        if (days.isEmpty()) return 0
        val sorted = days.toSortedSet()
        var best = 1
        var current = 1
        var previous: LocalDate? = null
        for (day in sorted) {
            current = if (previous != null && previous.plusDays(1) == day) current + 1 else 1
            if (current > best) best = current
            previous = day
        }
        return best
    }
}
