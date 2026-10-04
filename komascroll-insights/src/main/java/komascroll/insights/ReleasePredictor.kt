package komascroll.insights

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Estimates when the next chapter of a series is likely to appear from its chapter upload dates.
 *
 * Chapters uploaded on the same day count as one release. The cadence is the median gap between
 * recent releases; the prediction is withheld when there are too few releases, when the gaps
 * are irregular, or when the series is overdue by more than a full cadence (likely on hiatus).
 */
object ReleasePredictor {

    data class Prediction(
        /** Most likely release day (never before today). */
        val date: LocalDate,
        /** Typical number of days between releases. */
        val cadenceDays: Int,
        /** Set for weekly series that consistently release on the same weekday. */
        val weekday: DayOfWeek?,
    )

    /** Minimum number of distinct release days needed. */
    const val MIN_RELEASES = 5

    /** How many recent releases are considered. */
    private const val WINDOW = 9

    fun predict(uploadDates: List<Long>, today: LocalDate, zone: ZoneId): Prediction? {
        val days = uploadDates
            .filter { it > 0L }
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .filter { !it.isAfter(today) }
            .distinct()
            .sorted()
            .takeLast(WINDOW)
        if (days.size < MIN_RELEASES) return null

        val gaps = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
        val cadence = median(gaps)
        if (cadence < 1) return null

        // Irregular schedules (e.g. bulk uploads, random gaps) give no useful prediction.
        val deviation = median(gaps.map { abs(it - cadence) })
        if (deviation > max(1, cadence / 4)) return null

        val last = days.last()
        val weekday = if (cadence in 6..8) dominantWeekday(days) else null
        var next = if (weekday != null) {
            nextWeekdayAfter(last.plusDays(cadence - 3L), weekday)
        } else {
            last.plusDays(cadence.toLong())
        }

        if (next.isBefore(today)) {
            val overdue = ChronoUnit.DAYS.between(next, today)
            // Slightly late is normal; more than a full cadence late suggests a hiatus.
            if (overdue > max(2, deviation + 1)) return null
            next = today
        }
        return Prediction(next, cadence, weekday)
    }

    private fun dominantWeekday(days: List<LocalDate>): DayOfWeek? {
        val counts = days.groupingBy { it.dayOfWeek }.eachCount()
        val (weekday, count) = counts.maxByOrNull { it.value } ?: return null
        return if (count * 10 >= days.size * 6) weekday else null
    }

    private fun nextWeekdayAfter(date: LocalDate, weekday: DayOfWeek): LocalDate {
        var candidate = date.plusDays(1)
        while (candidate.dayOfWeek != weekday) candidate = candidate.plusDays(1)
        return candidate
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
