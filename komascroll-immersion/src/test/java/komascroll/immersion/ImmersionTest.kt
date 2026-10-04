package komascroll.immersion

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class ImmersionTest {

    // SfxClassifier

    @Test
    fun `classifies japanese effects including hiragana and stretched spellings`() {
        SfxClassifier.classify("ドカーン!!") shouldBe SfxKind.EXPLOSION
        SfxClassifier.classify("どきどき") shouldBe SfxKind.HEARTBEAT
        SfxClassifier.classify("ゴゴゴゴゴ") shouldBe SfxKind.RUMBLE
        SfxClassifier.classify("ドゴォッ") shouldBe SfxKind.IMPACT
        SfxClassifier.classify("ザシュッ") shouldBe SfxKind.SLASH
        SfxClassifier.classify("ガッシャーン") shouldBe SfxKind.CRASH
    }

    @Test
    fun `prefers the more specific sound`() {
        // ドドドン contains both a rumble and a hit; the longer, more specific sound wins.
        SfxClassifier.classify("ドドドン") shouldBe SfxKind.RUMBLE
        SfxClassifier.classify("ドン") shouldBe SfxKind.IMPACT
    }

    @Test
    fun `classifies english effects as whole words`() {
        SfxClassifier.classify("BOOOOOM!") shouldBe SfxKind.EXPLOSION
        SfxClassifier.classify("KA-BOOM") shouldBe SfxKind.EXPLOSION
        SfxClassifier.classify("Ba-dump") shouldBe SfxKind.HEARTBEAT
        SfxClassifier.classify("WHAM") shouldBe SfxKind.IMPACT
        SfxClassifier.classify("bomb").shouldBeNull()
        SfxClassifier.classify("bamboo").shouldBeNull()
    }

    @Test
    fun `classifies korean and chinese effects`() {
        SfxClassifier.classify("콰앙") shouldBe SfxKind.EXPLOSION
        SfxClassifier.classify("두근두근") shouldBe SfxKind.HEARTBEAT
        SfxClassifier.classify("砰") shouldBe SfxKind.IMPACT
    }

    @Test
    fun `ignores dialogue`() {
        SfxClassifier.classify("そんなことはドンだけ言っても無駄だよ、本当に").shouldBeNull()
        SfxClassifier.classify("I heard a boom outside the house").shouldBeNull()
        SfxClassifier.classify("").shouldBeNull()
    }

    @Test
    fun `strongest picks the biggest effect and skips tiny text`() {
        val hit = SfxClassifier.strongest(
            listOf(
                SfxCandidate("こんにちは", 0.02f),
                SfxCandidate("ドン", 0.03f),
                SfxCandidate("ゴゴゴ", 0.12f),
                SfxCandidate("BOOM", 0.005f),
            ),
        )
        hit.shouldNotBeNull()
        hit.kind shouldBe SfxKind.RUMBLE
        hit.strength shouldBe 1f
        SfxClassifier.strongest(listOf(SfxCandidate("こんにちは", 0.05f))).shouldBeNull()
        SfxClassifier.strongest(emptyList()).shouldBeNull()
    }

    // HapticPatterns

    @Test
    fun `waveforms scale with strength and keep pauses silent`() {
        SfxKind.entries.forEach { kind ->
            val loud = HapticPatterns.waveform(kind, 1f)
            val quiet = HapticPatterns.waveform(kind, 0f)
            loud.timings.size shouldBe loud.amplitudes.size
            loud.amplitudes.first() shouldBe 0
            (loud.amplitudes.max() >= quiet.amplitudes.max()) shouldBe true
            quiet.amplitudes.filter { it != 0 }.all { it in 1..255 } shouldBe true
            (loud.totalMillis in 50L..2000L) shouldBe true
        }
        HapticPatterns.waveform(SfxKind.IMPACT, 1f).amplitudes.max() shouldBe 255
        HapticPatterns.waveform(SfxKind.IMPACT, 1f, intensity = 0.5f).amplitudes.max() shouldBe 128
    }

    // PinHasher / LockoutPolicy

    @Test
    fun `pin hashes verify only the same pin`() {
        val stored = PinHasher.hash("2468")
        PinHasher.verify("2468", stored) shouldBe true
        PinHasher.verify("2469", stored) shouldBe false
        PinHasher.hash("2468") shouldNotBe stored
        PinHasher.verify("2468", "garbage") shouldBe false
        PinHasher.verify("2468", "v1:x:y:z") shouldBe false
    }

    @Test
    fun `pins must be 4 to 8 digits`() {
        PinHasher.isValidPin("1234") shouldBe true
        PinHasher.isValidPin("12345678") shouldBe true
        PinHasher.isValidPin("123") shouldBe false
        PinHasher.isValidPin("123456789") shouldBe false
        PinHasher.isValidPin("12a4") shouldBe false
    }

    @Test
    fun `lockout grows after free attempts and is capped`() {
        LockoutPolicy.lockoutMillis(0) shouldBe 0L
        LockoutPolicy.lockoutMillis(4) shouldBe 0L
        LockoutPolicy.lockoutMillis(5) shouldBe 30_000L
        LockoutPolicy.lockoutMillis(6) shouldBe 60_000L
        LockoutPolicy.lockoutMillis(50) shouldBe 15 * 60_000L
    }

    // SmartDownloadPlanner

    private fun chapter(id: Long, number: Double, read: Boolean = false, downloaded: Boolean = false) =
        PlannerChapter(id, number, sourceOrder = 100 - id, read = read, downloaded = downloaded)

    @Test
    fun `plans the next unread chapters after the last read one`() {
        val chapters = listOf(
            chapter(1, 1.0, read = true),
            chapter(2, 2.0, read = true),
            chapter(3, 3.0),
            chapter(4, 4.0, downloaded = true),
            chapter(5, 5.0),
            chapter(6, 6.0),
        )
        // Chapter 4 is already offline and counts towards the three ahead.
        SmartDownloadPlanner.plan(chapters.shuffled(), ahead = 3) shouldContainExactly listOf(3L, 5L)
    }

    @Test
    fun `skips series that were never started and handles nothing left`() {
        SmartDownloadPlanner.plan(listOf(chapter(1, 1.0), chapter(2, 2.0)), ahead = 3).shouldBeEmpty()
        SmartDownloadPlanner.plan(listOf(chapter(1, 1.0, read = true)), ahead = 3).shouldBeEmpty()
        SmartDownloadPlanner.plan(listOf(chapter(1, 1.0, read = true), chapter(2, 2.0)), ahead = 0).shouldBeEmpty()
    }

    @Test
    fun `falls back to source order when numbers are unknown`() {
        val chapters = listOf(
            PlannerChapter(10, -1.0, sourceOrder = 2, read = true, downloaded = false),
            PlannerChapter(11, -1.0, sourceOrder = 1, read = false, downloaded = false),
            PlannerChapter(12, 3.0, sourceOrder = 0, read = false, downloaded = false),
        )
        SmartDownloadPlanner.plan(chapters, ahead = 1) shouldContainExactly listOf(11L)
    }

    @Test
    fun `strength is reported for the loudest effect`() {
        val hit = SfxClassifier.strongest(listOf(SfxCandidate("バン", 0.02f), SfxCandidate("ばん", 0.01f)))
        hit.shouldNotBeNull()
        hit.strength shouldBeGreaterThan 0.29f
    }
}
