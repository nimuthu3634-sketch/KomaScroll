package komascroll.immersion

/** What the planner needs to know about one chapter. */
data class PlannerChapter(
    val id: Long,
    /** Recognized chapter number, or a negative value when unknown. */
    val number: Double,
    /** Source order: 0 is the newest chapter in most sources. */
    val sourceOrder: Long,
    val read: Boolean,
    /** Downloaded or already waiting in the download queue. */
    val downloaded: Boolean,
)

/**
 * Picks which chapters smart downloads should fetch for a series: the next unread chapters after
 * the furthest one read, keeping [ahead] unread chapters available offline.
 */
object SmartDownloadPlanner {

    fun plan(chapters: List<PlannerChapter>, ahead: Int): List<Long> {
        if (ahead <= 0 || chapters.isEmpty()) return emptyList()
        val ordered = readingOrder(chapters)
        val lastRead = ordered.indexOfLast { it.read }
        // Only plan for series the user has started; unread back catalogs are left alone.
        if (lastRead < 0) return emptyList()

        val upcoming = ordered.drop(lastRead + 1).filter { !it.read }.take(ahead)
        return upcoming.filter { !it.downloaded }.map { it.id }
    }

    /**
     * Oldest to newest. Recognized chapter numbers decide when every chapter has one; otherwise the
     * source order (newest first) is reversed, which is what most sources mean by it.
     */
    fun readingOrder(chapters: List<PlannerChapter>): List<PlannerChapter> =
        if (chapters.all { it.number >= 0 }) {
            chapters.sortedWith(compareBy<PlannerChapter> { it.number }.thenByDescending { it.sourceOrder })
        } else {
            chapters.sortedByDescending { it.sourceOrder }
        }
}
