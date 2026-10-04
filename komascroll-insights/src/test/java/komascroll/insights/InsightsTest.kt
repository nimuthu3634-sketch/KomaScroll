package komascroll.insights

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

class ReleasePredictorTest {

    private val zone = ZoneOffset.UTC

    private fun millis(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000

    @Test
    fun `weekly series predicts the usual weekday`() {
        // Fridays, every week.
        val lastFriday = LocalDate.of(2026, 9, 25)
        val uploads = (0 until 8).map { millis(lastFriday.minusWeeks(it.toLong())) }

        val prediction = ReleasePredictor.predict(uploads, today = LocalDate.of(2026, 9, 28), zone)

        prediction.shouldNotBeNull()
        prediction.weekday shouldBe DayOfWeek.FRIDAY
        prediction.date shouldBe LocalDate.of(2026, 10, 2)
        prediction.cadenceDays shouldBe 7
    }

    @Test
    fun `biweekly series predicts last release plus cadence`() {
        val last = LocalDate.of(2026, 9, 20)
        val uploads = (0 until 6).map { millis(last.minusDays(14L * it)) }

        val prediction = ReleasePredictor.predict(uploads, today = LocalDate.of(2026, 9, 25), zone)

        prediction.shouldNotBeNull()
        prediction.date shouldBe LocalDate.of(2026, 10, 4)
        prediction.weekday.shouldBeNull()
    }

    @Test
    fun `chapters uploaded on the same day count as one release`() {
        val last = LocalDate.of(2026, 9, 25)
        val uploads = (0 until 6).flatMap { week ->
            val day = last.minusWeeks(week.toLong())
            listOf(millis(day), millis(day) + 60_000)
        }

        ReleasePredictor.predict(uploads, today = last, zone)!!.cadenceDays shouldBe 7
    }

    @Test
    fun `too few releases give no prediction`() {
        val last = LocalDate.of(2026, 9, 25)
        val uploads = (0 until 3).map { millis(last.minusWeeks(it.toLong())) }

        ReleasePredictor.predict(uploads, today = last, zone).shouldBeNull()
    }

    @Test
    fun `irregular schedules give no prediction`() {
        val gaps = listOf(2, 30, 5, 60, 1, 14)
        var day = LocalDate.of(2026, 1, 1)
        val uploads = mutableListOf(millis(day))
        gaps.forEach {
            day = day.plusDays(it.toLong())
            uploads += millis(day)
        }

        ReleasePredictor.predict(uploads, today = day, zone).shouldBeNull()
    }

    @Test
    fun `series on hiatus gives no prediction`() {
        val last = LocalDate.of(2026, 6, 5)
        val uploads = (0 until 8).map { millis(last.minusWeeks(it.toLong())) }

        ReleasePredictor.predict(uploads, today = LocalDate.of(2026, 9, 28), zone).shouldBeNull()
    }

    @Test
    fun `slightly late release is predicted for today`() {
        val last = LocalDate.of(2026, 9, 18)
        val uploads = (0 until 8).map { millis(last.minusWeeks(it.toLong())) }

        ReleasePredictor.predict(uploads, today = LocalDate.of(2026, 9, 26), zone)!!.date shouldBe
            LocalDate.of(2026, 9, 26)
    }
}

class PerceptualHashTest {

    private fun gradient(width: Int, height: Int, invert: Boolean = false) = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val value = ((x * 255 / width) + (y * 7 % 40)).coerceIn(0, 255)
        if (invert) 255 - value else value
    }

    @Test
    fun `same image at different resolutions has the same hash`() {
        val big = PerceptualHash.dHash(gradient(900, 1300), 900, 1300)
        val small = PerceptualHash.dHash(gradient(450, 650), 450, 650)

        PerceptualHash.matches(big, small).shouldBeTrue()
    }

    @Test
    fun `different images do not match`() {
        val a = PerceptualHash.dHash(gradient(400, 600), 400, 600)
        val b = PerceptualHash.dHash(gradient(400, 600, invert = true), 400, 600)

        PerceptualHash.matches(a, b).shouldBeFalse()
    }

    @Test
    fun `distance counts differing bits`() {
        PerceptualHash.distance(0b1011L, 0b0001L) shouldBe 2
    }
}

class TitleSimilarityTest {

    @Test
    fun `punctuation, case and brackets are ignored`() {
        TitleSimilarity.similarity("Kaiju No. 8", "kaiju no 8 (Official)") shouldBe 1f
    }

    @Test
    fun `similar titles score high and unrelated titles low`() {
        (TitleSimilarity.similarity("The Apothecary Diaries", "Apothecary Diaries") > 0.8f).shouldBeTrue()
        (TitleSimilarity.similarity("One Piece", "Blue Lock") < 0.3f).shouldBeTrue()
    }
}

class ReadingStreaksTest {

    @Test
    fun `longest run of consecutive days`() {
        val days = listOf(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 2),
            LocalDate.of(2026, 1, 3),
            LocalDate.of(2026, 1, 10),
            LocalDate.of(2026, 1, 11),
        )

        ReadingStreaks.longest(days) shouldBe 3
        ReadingStreaks.longest(emptyList()) shouldBe 0
    }
}

class ChapterGapsTest {

    @Test
    fun `first gap in chapter numbers`() {
        ChapterGaps.firstMissing(listOf(1.0, 2.0, 2.5, 3.0, 6.0, 7.0)) shouldBe 4.0
        ChapterGaps.firstMissing(listOf(1.0, 2.0, 3.0)).shouldBeNull()
        ChapterGaps.firstMissing(listOf(-1.0, 5.0)).shouldBeNull()
    }
}
