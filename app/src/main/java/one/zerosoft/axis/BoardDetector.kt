package one.zerosoft.axis

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds a chess board on a screenshot: the axis-aligned square whose 8×8 cells
 * alternate light/dark most clearly. Cells are sampled near their corners,
 * where pieces rarely reach. Works on most chess apps and sites without
 * calibration; returns null when nothing board-like is on the screen.
 */
object BoardDetector {

    fun detect(bitmap: Bitmap): BoardRect? {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return detect(px, bitmap.width, bitmap.height)
    }

    /** [pixels] are ARGB, row-major, [width]×[height]. */
    fun detect(pixels: IntArray, width: Int, height: Int): BoardRect? {
        // 1. Downscale to ~360 px wide grayscale and build an integral image.
        val k = max(1, ceil(width / TARGET_WIDTH.toDouble()).toInt())
        val w = width / k
        val h = height / k
        if (w < 64 || h < 64) return null
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                var sum = 0
                for (dy in 0 until k) {
                    val base = (y * k + dy) * width + x * k
                    for (dx in 0 until k) {
                        val c = pixels[base + dx]
                        sum += (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                    }
                }
                rowSum += sum / (k * k)
                integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        val img = Gray(integral, w, h)

        // 2. Coarse search over size and position, then refine around the best.
        val minSide = min(w, h)
        val sMin = (minSide * 0.35f).roundToInt()
        val step = max(2, w / 90)
        var best = Candidate(0, 0, 0, 0f)
        var s = minSide
        while (s >= sMin) {
            var y = 0
            while (y + s <= h) {
                var x = 0
                while (x + s <= w) {
                    val sc = img.score(x, y, s, quick = true)
                    if (sc > best.score) best = Candidate(x, y, s, sc)
                    x += step
                }
                y += step
            }
            s -= step
        }
        if (best.score <= 0f) return null

        val coarse = best
        for (ds in -step..step) for (dy in -step..step) for (dx in -step..step) {
            val ns = coarse.s + ds
            val nx = coarse.x + dx
            val ny = coarse.y + dy
            if (ns < sMin || nx < 0 || ny < 0 || nx + ns > w || ny + ns > h) continue
            val sc = img.score(nx, ny, ns, quick = false)
            if (sc > best.score) best = Candidate(nx, ny, ns, sc)
        }
        if (best.score < MIN_SCORE) return null
        return BoardRect(best.x * k.toFloat(), best.y * k.toFloat(), best.s * k.toFloat())
    }

    private data class Candidate(val x: Int, val y: Int, val s: Int, val score: Float)

    private class Gray(val integral: LongArray, val w: Int, val h: Int) {
        private val stride = w + 1

        private fun mean(x: Int, y: Int, size: Int): Float {
            val x2 = min(w, x + size)
            val y2 = min(h, y + size)
            val area = (x2 - x) * (y2 - y)
            if (area <= 0) return 0f
            val sum = integral[y2 * stride + x2] - integral[y * stride + x2] - integral[y2 * stride + x] + integral[y * stride + x]
            return sum.toFloat() / area
        }

        /** Brightness of a cell from 4 small corner patches: mean of the middle two (ignores labels/pieces). */
        private fun cell(cx: Float, cy: Float, c: Float): Float {
            val p = max(1, (c * 0.2f).roundToInt())
            val q = c * 0.06f
            val x1 = (cx + q).roundToInt()
            val y1 = (cy + q).roundToInt()
            val x2 = (cx + c - q).roundToInt() - p
            val y2 = (cy + c - q).roundToInt() - p
            val a = mean(x1, y1, p); val b = mean(x2, y1, p); val d = mean(x1, y2, p); val e = mean(x2, y2, p)
            val lo = min(min(a, b), min(d, e))
            val hi = max(max(a, b), max(d, e))
            return (a + b + d + e - lo - hi) / 2f
        }

        /**
         * How much the square at (x, y, s) looks like a board: contrast between light and dark
         * cells relative to their spread (Fisher criterion). 0 = not a board.
         */
        fun score(x: Int, y: Int, s: Int, quick: Boolean): Float {
            val c = s / 8f
            // Fast reject: the first row must already alternate.
            if (quick) {
                var even = 0f; var odd = 0f
                for (i in 0 until 8) {
                    val v = cell(x + i * c, y.toFloat(), c)
                    if (i % 2 == 0) even += v else odd += v
                }
                if (abs(even - odd) / 4f < MIN_CONTRAST) return 0f
            }
            var sumA = 0f; var sqA = 0f; var sumB = 0f; var sqB = 0f
            for (j in 0 until 8) for (i in 0 until 8) {
                val v = cell(x + i * c, y + j * c, c)
                if ((i + j) % 2 == 0) { sumA += v; sqA += v * v } else { sumB += v; sqB += v * v }
            }
            val mA = sumA / 32f; val mB = sumB / 32f
            val diff = abs(mA - mB)
            if (diff < MIN_CONTRAST) return 0f
            val varA = sqA / 32f - mA * mA
            val varB = sqB / 32f - mB * mB
            return diff * diff / (varA + varB + 16f)
        }
    }

    private const val TARGET_WIDTH = 360
    private const val MIN_CONTRAST = 18f
    private const val MIN_SCORE = 6f
}
