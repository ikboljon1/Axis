package one.zerosoft.axis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChessHintTest {

    private val answer = """
        Ход: Кf3
        Идея: развитие коня и контроль центра.

        [[ХОД]]
        {"best": "g1f3", "alternatives": ["e2e4", "zz99", "g1f3"], "white_bottom": false, "board": [0.0, 0.25, 1.0, 0.7]}
        [[/ХОД]]
    """.trimIndent()

    @Test
    fun parsesBlockAndStripsItFromText() {
        val (text, hint) = ChessHint.parse(answer)
        assertEquals("Ход: Кf3\nИдея: развитие коня и контроль центра.", text)
        assertNotNull(hint)
        assertEquals("g1f3", hint!!.best)
        assertEquals(listOf("e2e4"), hint.alternatives) // invalid and duplicate moves dropped
        assertFalse(hint.whiteBottom)
    }

    @Test
    fun noBlockOrBrokenBlock() {
        assertNull(ChessHint.parse("Доску не видно").second)
        val (text, hint) = ChessHint.parse("Ход: ?\n[[ХОД]]\n{\"best\": \"e9e4\"}\n[[/ХОД]]")
        assertEquals("Ход: ?", text)
        assertNull(hint)
    }

    @Test
    fun squareCentersForBothOrientations() {
        val b = BoardRect(0f, 800f, 800f) // 100 px cells
        assertEquals(50f to 1550f, ChessHint.squareCenter("a1", b, whiteBottom = true))
        assertEquals(750f to 850f, ChessHint.squareCenter("h8", b, whiteBottom = true))
        assertEquals(750f to 850f, ChessHint.squareCenter("a1", b, whiteBottom = false))
        assertEquals(450f to 1150f, ChessHint.squareCenter("e5", b, whiteBottom = true))
        assertEquals(350f to 1250f, ChessHint.squareCenter("e5", b, whiteBottom = false))
    }

    @Test
    fun boardFromClaudeFractionIsSquare() {
        val hint = ChessHint.parse(answer).second!!
        val b = hint.boardFromFraction(1000, 2000)!! // 1000 x 900 px -> 950 square, same center
        assertEquals(950f, b.size, 0.01f)
        assertEquals(25f, b.left, 0.01f)
        assertEquals(500f + 450f - 475f, b.top, 0.01f)
    }
}
