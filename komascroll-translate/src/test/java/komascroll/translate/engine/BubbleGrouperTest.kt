package komascroll.translate.engine

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BubbleGrouperTest {

    @Test
    fun `vertical columns in one bubble are read right to left`() {
        // Two vertical columns, 30 px wide, 8 px apart; the right one comes first.
        val left = OcrLine("ですか", Box(100, 100, 130, 220))
        val right = OcrLine("元気", Box(138, 100, 168, 180))

        val groups = BubbleGrouper.group(listOf(left, right))

        groups shouldHaveSize 1
        groups[0].vertical shouldBe true
        groups[0].text shouldBe "元気ですか"
    }

    @Test
    fun `horizontal lines are read top to bottom and joined with spaces`() {
        val second = OcrLine("are you?", Box(100, 130, 220, 155))
        val first = OcrLine("How", Box(120, 100, 200, 125))

        val groups = BubbleGrouper.group(listOf(second, first))

        groups shouldHaveSize 1
        groups[0].vertical shouldBe false
        groups[0].text shouldBe "How are you?"
    }

    @Test
    fun `distant lines become separate bubbles`() {
        val a = OcrLine("こんにちは", Box(100, 100, 130, 260))
        val b = OcrLine("さようなら", Box(600, 900, 630, 1060))

        BubbleGrouper.group(listOf(a, b)) shouldHaveSize 2
    }

    @Test
    fun `vertical and horizontal neighbours are not merged`() {
        val vertical = OcrLine("縦書き", Box(100, 100, 130, 200))
        val horizontal = OcrLine("SIDE NOTE", Box(135, 100, 300, 125))

        BubbleGrouper.group(listOf(vertical, horizontal)) shouldHaveSize 2
    }

    @Test
    fun `duplicate detections from overlapping chunks are dropped`() {
        val a = OcrLine("同じ文", Box(100, 100, 130, 200))
        val b = OcrLine("同じ文", Box(101, 102, 131, 201))

        val groups = BubbleGrouper.group(listOf(a, b))

        groups shouldHaveSize 1
        groups[0].text shouldBe "同じ文"
    }

    @Test
    fun `groups without letters are ignored`() {
        BubbleGrouper.group(listOf(OcrLine("!?…", Box(0, 0, 40, 20)))) shouldHaveSize 0
    }

    @Test
    fun `hyphenated latin words are rejoined`() {
        BubbleGrouper.joinLines(listOf("transla-", "tion works")) shouldBe "translation works"
    }

    @Test
    fun `single characters have no orientation`() {
        BubbleGrouper.orientationOf(OcrLine("あ", Box(0, 0, 20, 20))) shouldBe null
    }
}
