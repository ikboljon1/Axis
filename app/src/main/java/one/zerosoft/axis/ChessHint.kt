package one.zerosoft.axis

import org.json.JSONObject

/** Square board on the screen, in screen pixels. */
data class BoardRect(val left: Float, val top: Float, val size: Float) {
    val cell: Float get() = size / 8f
}

/**
 * Machine-readable part of Claude's chess answer: moves as from-to squares
 * ("e2e4"), board orientation and a rough board frame (fractions of the image),
 * used when the phone can't find the board itself.
 */
data class ChessHint(
    val best: String,
    val alternatives: List<String>,
    val whiteBottom: Boolean,
    /** [left, top, right, bottom] as fractions of the screenshot, or null. */
    val boardFraction: List<Float>?,
) {
    fun boardFromFraction(imageWidth: Int, imageHeight: Int): BoardRect? {
        val f = boardFraction ?: return null
        val l = f[0] * imageWidth
        val t = f[1] * imageHeight
        val w = (f[2] - f[0]) * imageWidth
        val h = (f[3] - f[1]) * imageHeight
        if (w <= 0f || h <= 0f) return null
        val size = (w + h) / 2f // the board is square; keep its center
        return BoardRect(l + (w - size) / 2f, t + (h - size) / 2f, size)
    }

    companion object {
        private val BLOCK = Regex("""\[\[ХОД]](.*?)(?:\[\[/ХОД]]|\z)""", RegexOption.DOT_MATCHES_ALL)
        private val MOVE = Regex("^[a-h][1-8][a-h][1-8][qrbn]?$")

        /** Splits an answer into the text for the user and the hint block (if any). */
        fun parse(answer: String): Pair<String, ChessHint?> {
            val match = BLOCK.find(answer) ?: return answer.trim() to null
            val text = answer.removeRange(match.range).trim()
            val hint = runCatching {
                val raw = match.groupValues[1].trim().removePrefix("```json").removePrefix("```").removeSuffix("```")
                val json = JSONObject(raw.trim())
                val best = json.getString("best").trim().lowercase()
                require(MOVE.matches(best))
                val alts = json.optJSONArray("alternatives")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it).trim().lowercase() }.filter { MOVE.matches(it) && it != best }
                }.orEmpty().take(2)
                val board = json.optJSONArray("board")?.let { arr ->
                    if (arr.length() == 4) (0 until 4).map { arr.getDouble(it).toFloat().coerceIn(0f, 1f) } else null
                }
                ChessHint(best, alts, json.optBoolean("white_bottom", true), board)
            }.getOrNull()
            return text to hint
        }

        /** Center of a square ("e4") on the screen. */
        fun squareCenter(square: String, board: BoardRect, whiteBottom: Boolean): Pair<Float, Float> {
            val file = square[0] - 'a'   // 0..7, a..h
            val rank = square[1] - '1'   // 0..7, 1..8
            val col = if (whiteBottom) file else 7 - file
            val row = if (whiteBottom) 7 - rank else rank
            return (board.left + (col + 0.5f) * board.cell) to (board.top + (row + 0.5f) * board.cell)
        }
    }
}
