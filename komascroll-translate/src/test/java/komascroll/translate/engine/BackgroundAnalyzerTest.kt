package komascroll.translate.engine

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class BackgroundAnalyzerTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** 200x200 page: a white bubble (40..160) with a black outline, grey screentone outside. */
    private fun bubblePage(): PixelRegion {
        val size = 200
        val grey = 0xFF808080.toInt()
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            when {
                x in 40..160 && y in 40..160 && (x == 40 || x == 160 || y == 40 || y == 160) -> black
                x in 41..159 && y in 41..159 -> if (x in 90..110 && y in 70..130 && (x + y) % 3 == 0) black else white
                else -> if ((x + y) % 2 == 0) grey else white
            }
        }
        return PixelRegion(pixels, size, size)
    }

    @Test
    fun `ring around text inside a white bubble is uniform white`() {
        val sample = BackgroundAnalyzer.sampleRing(bubblePage(), Box(90, 70, 111, 131), ringWidth = 8)

        sample shouldNotBe null
        sample!!.color shouldBe white
        sample.isUniform.shouldBeTrue()
    }

    @Test
    fun `screentone background is not uniform`() {
        val sample = BackgroundAnalyzer.sampleRing(bubblePage(), Box(170, 170, 190, 190), ringWidth = 6)

        sample!!.isUniform.shouldBeFalse()
    }

    @Test
    fun `interior estimate grows to the bubble outline and stops there`() {
        val interior = BackgroundAnalyzer.estimateInterior(bubblePage(), Box(90, 70, 111, 131), white, maxExpand = 100)

        interior shouldBe Box(41, 41, 160, 160)
    }

    @Test
    fun `luminance separates light and dark colours`() {
        (BackgroundAnalyzer.luminance(white) > 0.5f).shouldBeTrue()
        (BackgroundAnalyzer.luminance(black) < 0.5f).shouldBeTrue()
    }
}
