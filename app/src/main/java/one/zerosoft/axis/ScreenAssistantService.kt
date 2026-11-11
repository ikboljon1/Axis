package one.zerosoft.axis

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the screen capture session and the overlay.
 * Started by [MainActivity] with the result of the screen-capture consent dialog.
 */
class ScreenAssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var overlay: OverlayController? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY // already running

        // Must be in the foreground (type mediaProjection) before getMediaProjection().
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data) ?: run { stopSelf(); return START_NOT_STICKY }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, Handler(Looper.getMainLooper()))
        projection = mp

        val m = screenMetrics()
        capturer = ScreenCapturer(mp, m.widthPixels, m.heightPixels, m.densityDpi)

        overlay = OverlayController(
            context = this,
            onAnalyze = ::analyze,
            onModeChanged = {},
            onStop = { stopSelf() },
        ).also { it.show(Prefs(this).mode) }

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val m = screenMetrics()
        capturer?.resize(m.widthPixels, m.heightPixels)
    }

    private fun analyze() {
        if (job?.isActive == true) return
        val prefs = Prefs(this)
        val mode = prefs.mode
        val ov = overlay ?: return
        val cap = capturer ?: return

        if (prefs.apiKey.isBlank()) {
            ov.showResult(mode, "Не задан API-ключ Claude. Откройте приложение Axis и введите ключ.")
            return
        }

        job = scope.launch {
            // Hide our own views so they are not in the screenshot, wait for a fresh frame.
            ov.setBubbleVisible(false)
            delay(350)
            val shot = cap.capture()
            ov.setBubbleVisible(true)

            if (shot == null) {
                ov.showResult(mode, "Не удалось получить изображение экрана. Попробуйте ещё раз.")
                return@launch
            }

            ov.showLoading(mode)
            val answer = withContext(Dispatchers.IO) {
                try {
                    ClaudeAnalyzer(prefs.apiKey).analyze(shot, mode, prefs.customPrompt, prefs.precise)
                } catch (e: Exception) {
                    "Ошибка: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    shot.recycle()
                }
            }
            ov.showResult(mode, answer)
        }
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            metrics.widthPixels = b.width()
            metrics.heightPixels = b.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Анализ экрана", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eye)
            .setContentTitle("Axis видит экран")
            .setContentText("Нажмите на плавающую кнопку, чтобы получить подсказку")
            .setContentIntent(openApp)
            .addAction(0, "Остановить", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        overlay?.remove()
        capturer?.release()
        projection?.stop()
        overlay = null; capturer = null; projection = null
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "one.zerosoft.axis.STOP"
        private const val CHANNEL_ID = "axis_capture"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set
    }
}
