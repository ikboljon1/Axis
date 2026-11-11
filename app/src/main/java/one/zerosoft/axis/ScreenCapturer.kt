package one.zerosoft.axis

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread

/**
 * Mirrors the screen into an [ImageReader] through a single [VirtualDisplay]
 * (on Android 14+ a MediaProjection token may create only one) and keeps the
 * newest frame so a screenshot can be taken at any moment.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    width: Int,
    height: Int,
    private val densityDpi: Int,
) {
    private val thread = HandlerThread("axis-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()

    private var latest: Image? = null
    private var reader: ImageReader = newReader(width, height)
    private val display: VirtualDisplay

    init {
        display = projection.createVirtualDisplay(
            "axis-screen", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )
    }

    @SuppressLint("WrongConstant")
    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                synchronized(lock) {
                    latest?.close()
                    latest = img
                }
            }, handler)
        }

    /** Screen rotated: resize the virtual display instead of creating a new one. */
    fun resize(width: Int, height: Int) {
        if (width == reader.width && height == reader.height) return
        val old = reader
        reader = newReader(width, height)
        display.resize(width, height, densityDpi)
        display.surface = reader.surface
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        old.close()
    }

    /** Returns a copy of the newest frame, or null if none has arrived yet. */
    fun capture(): Bitmap? = synchronized(lock) {
        val img = latest ?: return null
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val padded = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (padded.width == img.width) padded
        else Bitmap.createBitmap(padded, 0, 0, img.width, img.height).also { padded.recycle() }
    }

    fun release() {
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        display.release()
        reader.close()
        thread.quitSafely()
    }
}
