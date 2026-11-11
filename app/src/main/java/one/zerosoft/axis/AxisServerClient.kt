package one.zerosoft.axis

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Talks to the Axis server (server/axis_server.py), which runs Claude Code on
 * your Claude subscription. The phone holds no Claude key — only the server URL
 * and a personal access token. Blocking: call from a background dispatcher.
 */
class AxisServerClient(serverUrl: String, private val token: String) {

    private val base = serverUrl.trim().trimEnd('/')

    fun ping(): String {
        val (code, json) = request("GET", "$base/v1/ping", null, timeoutMs = 15_000)
        return if (code == 200 && json.optBoolean("ok")) "Сервер на связи ✅"
        else json.optString("error").ifBlank { "Сервер ответил кодом $code" }
    }

    fun analyze(screen: Bitmap, mode: AnalysisMode, customPrompt: String, precise: Boolean): String {
        val question = when (mode) {
            AnalysisMode.CUSTOM -> customPrompt.ifBlank { "Что на этом экране?" }
            else -> mode.userPrompt
        }
        val body = JSONObject()
            .put("system", mode.systemPrompt)
            .put("prompt", question)
            .put("image", encodeJpeg(screen))
            .put("precise", precise)

        val (code, json) = request("POST", "$base/v1/analyze", body.toString(), timeoutMs = 240_000)
        return when {
            json.optBoolean("ok") -> json.optString("text").ifBlank { "Пустой ответ." }
            code == 401 -> "Неверный токен доступа. Проверьте настройки Axis."
            else -> json.optString("error").ifBlank { "Ошибка сервера (код $code)" }
        }
    }

    /** Server answer for the chess mode (Claude reads the board, Stockfish picks the move). */
    sealed class ChessReply {
        data class Ok(val text: String, val hint: ChessHint?) : ChessReply()
        data class Failed(val text: String) : ChessReply()
        /** Old server or no Stockfish there — use [analyze] with the chess prompt instead. */
        data object NotSupported : ChessReply()
    }

    fun chess(boardImage: Bitmap, precise: Boolean): ChessReply {
        val body = JSONObject()
            .put("image", encodeJpeg(boardImage))
            .put("precise", precise)
        val (code, json) = request("POST", "$base/v1/chess", body.toString(), timeoutMs = 240_000)
        return when {
            json.optBoolean("ok") -> ChessReply.Ok(json.optString("text"), ChessHint.fromJson(json))
            code == 404 || json.optString("code") == "no_engine" -> ChessReply.NotSupported
            code == 401 -> ChessReply.Failed("Неверный токен доступа. Проверьте настройки Axis.")
            else -> ChessReply.Failed(json.optString("error").ifBlank { "Ошибка сервера (код $code)" })
        }
    }

    private fun request(method: String, url: String, body: String?, timeoutMs: Int): Pair<Int, JSONObject> {
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            throw IOException("Адрес сервера должен начинаться с https://")
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            return code to json
        } finally {
            conn.disconnect()
        }
    }

    /** Downscale so the long edge is ≤ MAX_EDGE px (what Claude vision works best with) and JPEG-encode. */
    private fun encodeJpeg(src: Bitmap): String {
        val longEdge = max(src.width, src.height)
        val scaled = if (longEdge > MAX_EDGE) {
            val k = MAX_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true)
        } else src
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== src) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private companion object {
        const val MAX_EDGE = 1568
    }
}
