package komascroll.panels

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PanelDetectorTest {

    private val width = 400
    private val height = 600

    /** A blank page of [background] gray with panels drawn as framed boxes with some "art" inside. */
    private fun page(background: Int, vararg panels: IntArray, fill: Int = 255, ink: Int = 0): IntArray {
        val gray = IntArray(width * height) { background }
        for ((left, top, right, bottom) in panels) {
            for (y in top until bottom) {
                for (x in left until right) {
                    val onFrame = x < left + 3 || x >= right - 3 || y < top + 3 || y >= bottom - 3
                    val art = (x * 7 + y * 3) % 23 == 0
                    gray[y * width + x] = if (onFrame || art) ink else fill
                }
            }
        }
        return gray
    }

    private fun box(left: Int, top: Int, right: Int, bottom: Int) = intArrayOf(left, top, right, bottom)

    private fun PanelBox.centerX() = (left + right) / 2
    private fun PanelBox.centerY() = (top + bottom) / 2

    @Test
    fun `two tiers are read top to bottom and right to left for manga`() {
        val gray = page(
            255,
            box(20, 20, 190, 280),
            box(210, 20, 380, 280),
            box(20, 300, 380, 580),
        )

        val layout = PanelDetector.detect(gray, width, height, rightToLeft = true)

        layout.isUsable.shouldBeTrue()
        layout.panels shouldHaveSize 3
        (layout.panels[0].centerX() > 0.5f).shouldBeTrue() // top-right first
        (layout.panels[1].centerX() < 0.5f).shouldBeTrue() // then top-left
        (layout.panels[2].centerY() > 0.5f).shouldBeTrue() // then the bottom tier
    }

    @Test
    fun `left to right order for western comics`() {
        val gray = page(
            255,
            box(20, 20, 190, 280),
            box(210, 20, 380, 280),
            box(20, 300, 380, 580),
        )

        val layout = PanelDetector.detect(gray, width, height, rightToLeft = false)

        (layout.panels[0].centerX() < 0.5f).shouldBeTrue()
        (layout.panels[1].centerX() > 0.5f).shouldBeTrue()
    }

    @Test
    fun `tall panel on the right comes before the stacked left column`() {
        val gray = page(
            255,
            box(210, 20, 380, 580),
            box(20, 20, 190, 290),
            box(20, 310, 190, 580),
        )

        val layout = PanelDetector.detect(gray, width, height, rightToLeft = true)

        layout.panels shouldHaveSize 3
        (layout.panels[0].centerX() > 0.5f).shouldBeTrue()
        (layout.panels[1].centerY() < layout.panels[2].centerY()).shouldBeTrue()
    }

    @Test
    fun `black gutters are detected too`() {
        val gray = page(
            0,
            box(20, 20, 380, 290),
            box(20, 310, 380, 580),
            fill = 255,
            ink = 60,
        )

        val layout = PanelDetector.detect(gray, width, height, rightToLeft = true)

        layout.isUsable.shouldBeTrue()
        layout.panels shouldHaveSize 2
        layout.panels[0].top shouldBe (20f / height plusOrMinus 0.01f)
    }

    @Test
    fun `full bleed splash page falls back to the full page`() {
        val gray = IntArray(width * height) { index -> if (index % 5 == 0) 0 else 200 }

        PanelDetector.detect(gray, width, height, rightToLeft = true).isUsable.shouldBeFalse()
    }

    @Test
    fun `page numbers in the margin are ignored`() {
        val gray = page(
            255,
            box(20, 20, 380, 280),
            box(20, 300, 380, 560),
            box(195, 575, 205, 590),
        )

        val layout = PanelDetector.detect(gray, width, height, rightToLeft = true)

        layout.panels shouldHaveSize 2
    }

    @Test
    fun `padding stays inside the page`() {
        val padded = PanelBox(0f, 0.1f, 0.5f, 1f).padded(0.1f)

        padded.left shouldBe 0f
        padded.bottom shouldBe 1f
        padded.top shouldBe (0.01f plusOrMinus 0.0001f)
    }
}
