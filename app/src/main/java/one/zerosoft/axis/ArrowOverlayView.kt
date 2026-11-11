package one.zerosoft.axis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Full-screen transparent layer that draws move arrows on top of the chess
 * board: the best move in green, alternatives thinner in blue. Board
 * coordinates are screen pixels; the view offsets itself by its own position.
 */
class ArrowOverlayView(context: Context) : View(context) {

    private var board: BoardRect? = null
    private var hint: ChessHint? = null
    private val loc = IntArray(2)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val square = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }

    fun show(board: BoardRect, hint: ChessHint) {
        this.board = board
        this.hint = hint
        invalidate()
    }

    fun clear() {
        board = null
        hint = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = board ?: return
        val h = hint ?: return
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

        h.alternatives.forEach { drawMove(canvas, it, b, h.whiteBottom, ALT_COLOR, 0.13f, highlight = false) }
        drawMove(canvas, h.best, b, h.whiteBottom, BEST_COLOR, 0.2f, highlight = true)
        canvas.restore()
    }

    private fun drawMove(canvas: Canvas, move: String, b: BoardRect, whiteBottom: Boolean, color: Int, width: Float, highlight: Boolean) {
        val (x1, y1) = ChessHint.squareCenter(move.substring(0, 2), b, whiteBottom)
        val (x2, y2) = ChessHint.squareCenter(move.substring(2, 4), b, whiteBottom)
        val cell = b.cell

        if (highlight) {
            square.color = SQUARE_COLOR
            canvas.drawRect(x1 - cell / 2, y1 - cell / 2, x1 + cell / 2, y1 + cell / 2, square)
            canvas.drawRect(x2 - cell / 2, y2 - cell / 2, x2 + cell / 2, y2 + cell / 2, square)
        }

        val angle = atan2(y2 - y1, x2 - x1)
        val len = hypot(x2 - x1, y2 - y1)
        val shaft = cell * width
        val headLen = cell * 0.45f
        val headHalf = cell * (width + 0.12f)
        val start = cell * 0.25f
        if (len <= start + headLen) return

        // Arrow pointing along +x, then rotated: shaft from the source square to the arrow head.
        val path = Path().apply {
            moveTo(start, -shaft / 2)
            lineTo(len - headLen, -shaft / 2)
            lineTo(len - headLen, -headHalf)
            lineTo(len, 0f)
            lineTo(len - headLen, headHalf)
            lineTo(len - headLen, shaft / 2)
            lineTo(start, shaft / 2)
            close()
        }
        canvas.save()
        canvas.translate(x1, y1)
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
        fill.color = color
        canvas.drawPath(path, fill)
        outline.strokeWidth = cell * 0.03f
        canvas.drawPath(path, outline)
        canvas.restore()
    }

    private companion object {
        val BEST_COLOR = Color.argb(215, 46, 204, 64)
        val ALT_COLOR = Color.argb(170, 52, 120, 246)
        val SQUARE_COLOR = Color.argb(90, 255, 235, 59)
    }
}
