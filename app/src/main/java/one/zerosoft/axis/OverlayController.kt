package one.zerosoft.axis

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import one.zerosoft.axis.databinding.OverlayBubbleBinding
import one.zerosoft.axis.databinding.OverlayResultBinding
import kotlin.math.abs

/**
 * Floating views drawn over other apps: a draggable bubble (tap = analyze,
 * long-press = switch mode) and a panel that shows Claude's answer.
 */
class OverlayController(
    private val context: Context,
    private val onAnalyze: () -> Unit,
    private val onModeChanged: (AnalysisMode) -> Unit,
    private val onStop: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(context)

    private val bubble = OverlayBubbleBinding.inflate(inflater)
    private val panel = OverlayResultBinding.inflate(inflater)

    private val bubbleParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24
        y = 400
    }

    private val panelParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM
    }

    private var panelShown = false

    init {
        setupBubble()
        panel.closeButton.setOnClickListener { hidePanel() }
        panel.retryButton.setOnClickListener { onAnalyze() }
        panel.stopButton.setOnClickListener { onStop() }
    }

    fun show(mode: AnalysisMode) {
        bubble.modeLabel.text = mode.title
        wm.addView(bubble.root, bubbleParams)
    }

    fun setBubbleVisible(visible: Boolean) {
        bubble.root.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (panelShown) panel.root.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun showLoading(mode: AnalysisMode) {
        bubble.progress.visibility = View.VISIBLE
        showPanel()
        panel.title.text = mode.title
        panel.resultText.text = "Анализирую экран…"
        panel.retryButton.isEnabled = false
    }

    fun showResult(mode: AnalysisMode, text: String) {
        bubble.progress.visibility = View.GONE
        showPanel()
        panel.title.text = mode.title
        panel.resultText.text = text
        panel.scroll.scrollTo(0, 0)
        panel.retryButton.isEnabled = true
    }

    fun remove() {
        runCatching { wm.removeView(bubble.root) }
        hidePanel()
    }

    private fun showPanel() {
        if (!panelShown) {
            wm.addView(panel.root, panelParams)
            panelShown = true
        }
        panel.root.visibility = View.VISIBLE
    }

    private fun hidePanel() {
        if (panelShown) {
            runCatching { wm.removeView(panel.root) }
            panelShown = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubble() {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        val slop = 12 * context.resources.displayMetrics.density

        bubble.button.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x; startY = bubbleParams.y
                    touchX = e.rawX; touchY = e.rawY
                    dragged = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!dragged && (abs(dx) > slop || abs(dy) > slop)) {
                        dragged = true
                        v.cancelLongPress()
                    }
                    if (dragged) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        wm.updateViewLayout(bubble.root, bubbleParams)
                    }
                    dragged
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) { v.isPressed = false; true } else false
                }
                else -> false
            }
        }
        bubble.button.setOnClickListener { onAnalyze() }
        bubble.button.setOnLongClickListener {
            val next = Prefs(context).mode.next()
            Prefs(context).mode = next
            bubble.modeLabel.text = next.title
            Toast.makeText(context, "Режим: ${next.title}", Toast.LENGTH_SHORT).show()
            onModeChanged(next)
            true
        }
    }
}
