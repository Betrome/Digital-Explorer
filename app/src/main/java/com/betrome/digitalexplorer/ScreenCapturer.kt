package com.betrome.digitalexplorer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

class ScreenCapturer(ctx: Context, private val projection: MediaProjection) {
    val width: Int
    val height: Int
    private val reader: ImageReader
    private val display: VirtualDisplay
    private var last: Bitmap? = null

    init {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = wm.currentWindowMetrics.bounds
        width = b.width(); height = b.height()
        val dpi = ctx.resources.displayMetrics.let { if (it.densityDpi > 0) it.densityDpi else DisplayMetrics.DENSITY_DEFAULT }
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val handler = Handler(Looper.getMainLooper())
        // Android 14 requires a callback to be registered before creating the virtual display.
        projection.registerCallback(object : MediaProjection.Callback() {}, handler)
        display = projection.createVirtualDisplay(
            "digital-explorer", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
        )
    }

    /** Latest screen frame (cached if the screen has not changed since the last call). */
    @Synchronized
    fun grab(): Bitmap? {
        val img = reader.acquireLatestImage() ?: return last
        try {
            val plane = img.planes[0]
            val padded = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(padded, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            last = if (padded == width) full else Bitmap.createBitmap(full, 0, 0, width, height)
        } finally {
            img.close()
        }
        return last
    }

    fun release() {
        display.release()
        reader.close()
        projection.stop()
    }
}
