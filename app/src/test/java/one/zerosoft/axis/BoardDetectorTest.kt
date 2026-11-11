package one.zerosoft.axis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Synthetic phone screenshots: UI around, a board with pieces, labels and a highlighted last move. */
class BoardDetectorTest {

    private class Canvas(val w: Int, val h: Int) {
        val px = IntArray(w * h)
        fun rect(x: Int, y: Int, rw: Int, rh: Int, c: Int) {
            for (yy in maxOf(0, y) until minOf(h, y + rh)) for (xx in maxOf(0, x) until minOf(w, x + rw)) px[yy * w + xx] = c
        }
        fun circle(cx: Int, cy: Int, r: Int, c: Int) {
            for (yy in cy - r..cy + r) for (xx in cx - r..cx + r) {
                if (xx in 0 until w && yy in 0 until h && (xx - cx) * (xx - cx) + (yy - cy) * (yy - cy) <= r * r) px[yy * w + xx] = c
            }
        }
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun screenWithBoard(
        w: Int, h: Int, left: Int, top: Int, size: Int, light: Int, dark: Int, bg: Int, seed: Int,
    ): Canvas {
        val c = Canvas(w, h)
        val rnd = Random(seed)
        c.rect(0, 0, w, h, bg)
        // some UI: header, player bars, buttons
        c.rect(0, 0, w, 90, rgb(30, 30, 30))
        c.rect(30, top - 140, w - 60, 100, rgb(60, 60, 60))
        c.rect(30, top + size + 40, w - 60, 100, rgb(60, 60, 60))
        repeat(6) { c.rect(rnd.nextInt(w - 200), top + size + 200 + rnd.nextInt(300), 180, 120, rgb(90, 90, 200)) }
        val cell = size / 8
        for (j in 0 until 8) for (i in 0 until 8) {
            c.rect(left + i * cell, top + j * cell, cell, cell, if ((i + j) % 2 == 0) light else dark)
        }
        // last move highlight
        c.rect(left + 4 * cell, top + 6 * cell, cell, cell, rgb(246, 246, 105))
        c.rect(left + 4 * cell, top + 4 * cell, cell, cell, rgb(186, 202, 68))
        // coordinate labels in corners of edge squares
        for (i in 0 until 8) {
            c.rect(left + i * cell + cell - cell / 6, top + 8 * cell - cell / 5, cell / 9, cell / 7, rgb(20, 20, 20))
            c.rect(left + cell / 20, top + i * cell + cell / 20, cell / 9, cell / 7, rgb(20, 20, 20))
        }
        // pieces on the first two and last two ranks + a few in the middle
        for (i in 0 until 8) for (j in listOf(0, 1, 6, 7)) {
            val color = if (j < 2) rgb(40, 40, 40) else rgb(250, 250, 250)
            c.circle(left + i * cell + cell / 2, top + j * cell + cell / 2, (cell * 0.38).toInt(), color)
        }
        repeat(5) {
            val i = rnd.nextInt(8); val j = 2 + rnd.nextInt(4)
            c.circle(left + i * cell + cell / 2, top + j * cell + cell / 2, (cell * 0.36).toInt(),
                if (rnd.nextBoolean()) rgb(40, 40, 40) else rgb(250, 250, 250))
        }
        return c
    }

    private fun assertBoard(found: BoardRect?, left: Int, top: Int, size: Int) {
        assertNotNull("board not found", found)
        val tol = size * 0.02f + 4
        assertEquals("left", left.toFloat(), found!!.left, tol)
        assertEquals("top", top.toFloat(), found.top, tol)
        assertEquals("size", size.toFloat(), found.size, tol)
    }

    @Test
    fun findsFullWidthGreenBoard() {
        val c = screenWithBoard(1080, 2400, 0, 640, 1080, rgb(235, 236, 208), rgb(119, 149, 86), rgb(48, 46, 43), 1)
        assertBoard(BoardDetector.detect(c.px, c.w, c.h), 0, 640, 1080)
    }

    @Test
    fun findsBrownBoardWithMargins() {
        val c = screenWithBoard(1080, 2340, 44, 520, 992, rgb(240, 217, 181), rgb(181, 136, 99), rgb(22, 21, 18), 2)
        assertBoard(BoardDetector.detect(c.px, c.w, c.h), 44, 520, 992)
    }

    @Test
    fun findsSmallBlueBoardOnLightUi() {
        val c = screenWithBoard(720, 1600, 60, 300, 600, rgb(222, 227, 230), rgb(140, 162, 173), rgb(245, 245, 245), 3)
        assertBoard(BoardDetector.detect(c.px, c.w, c.h), 60, 300, 600)
    }

    @Test
    fun noBoardOnRegularScreen() {
        val c = Canvas(1080, 2400)
        val rnd = Random(7)
        c.rect(0, 0, c.w, c.h, rgb(250, 250, 250))
        repeat(40) {
            c.rect(rnd.nextInt(1000), rnd.nextInt(2300), 80 + rnd.nextInt(400), 30 + rnd.nextInt(120),
                rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
        }
        assertNull(BoardDetector.detect(c.px, c.w, c.h))
    }

    @Test
    fun timing() {
        val c = screenWithBoard(1080, 2400, 0, 640, 1080, rgb(235, 236, 208), rgb(119, 149, 86), rgb(48, 46, 43), 4)
        BoardDetector.detect(c.px, c.w, c.h)
        val t = System.nanoTime()
        BoardDetector.detect(c.px, c.w, c.h)
        val ms = (System.nanoTime() - t) / 1_000_000
        println("BoardDetector: $ms ms on 1080x2400")
        assert(abs(ms) < 3000)
    }
}
