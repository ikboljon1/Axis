package one.zerosoft.axis

import android.graphics.Bitmap
import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.io.ByteArrayOutputStream
import java.time.Duration
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sends a screenshot to Claude (vision) and returns the text answer.
 * Blocking — call it from a background dispatcher.
 */
class ClaudeAnalyzer(apiKey: String) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofMinutes(3))
        .build()

    fun analyze(screen: Bitmap, mode: AnalysisMode, customPrompt: String, precise: Boolean): String {
        val imageB64 = encodeJpeg(screen)

        val question = when (mode) {
            AnalysisMode.CUSTOM -> customPrompt.ifBlank { "Что на этом экране?" }
            else -> mode.userPrompt
        }

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .system(mode.systemPrompt)
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(if (precise) BetaOutputConfig.Effort.HIGH else BetaOutputConfig.Effort.LOW)
                    .build()
            )
            // If the model declines, the server retries on a fallback model automatically.
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacksDefault()
            .addUserMessageOfContentBlockParams(
                listOf(
                    BetaContentBlockParam.ofImage(
                        BetaImageBlockParam.builder()
                            .source(
                                BetaBase64ImageSource.builder()
                                    .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                                    .data(imageB64)
                                    .build()
                            )
                            .build()
                    ),
                    BetaContentBlockParam.ofText(question),
                )
            )
            .build()

        val response = try {
            client.beta().messages().create(params)
        } catch (e: UnauthorizedException) {
            return "Неверный API-ключ. Проверьте ключ в настройках приложения."
        } catch (e: RateLimitException) {
            return "Слишком много запросов. Подождите немного и попробуйте снова."
        } catch (e: AnthropicServiceException) {
            return "Ошибка API (${e.statusCode()}): ${e.message}"
        } catch (e: AnthropicIoException) {
            return "Нет соединения с сервером: ${e.message}"
        }

        val stop = response.stopReason().orElse(null)
        if (stop == BetaStopReason.REFUSAL) {
            val why = response.stopDetails().flatMap { it.explanation() }.orElse("")
            return "Модель отказалась отвечать на этот запрос. $why".trim()
        }

        val text = response.content()
            .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString("\n")
            .trim()

        return when {
            text.isEmpty() -> "Пустой ответ от модели."
            stop == BetaStopReason.MAX_TOKENS -> "$text\n\n(ответ обрезан)"
            else -> text
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

    companion object {
        const val MODEL = "claude-opus-5-5"
        private const val MAX_EDGE = 1568
    }
}
