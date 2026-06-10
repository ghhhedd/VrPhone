package com.vrphone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object VrFrameBus {
    val screenBitmap = AtomicReference<Bitmap?>(null)
    val cameraBitmap = AtomicReference<Bitmap?>(null)
    val overlayAlpha = AtomicInteger(204)
    val mode = AtomicReference(VrMode.CINEMA)

    fun clear() {
        screenBitmap.getAndSet(null)?.recycle()
        cameraBitmap.getAndSet(null)?.recycle()
    }

    fun setScreenFrame(bitmap: Bitmap) {
        screenBitmap.getAndSet(bitmap)?.recycle()
    }

    fun setCameraFrame(bitmap: Bitmap) {
        cameraBitmap.getAndSet(bitmap)?.recycle()
    }
}

enum class VrMode { CINEMA, AR }

class VrSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var renderJob: Job? = null

    init {
        holder.addCallback(this)
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderJob = scope.launch {
            while (true) {
                drawFrame()
                delay(16L)
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderJob?.cancel()
        renderJob = null
    }

    private suspend fun drawFrame() = withContext(Dispatchers.Main.immediate) {
        val canvas = holder.lockCanvas() ?: return@withContext
        try {
            render(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun render(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        if (VrFrameBus.mode.get() == VrMode.AR) {
            VrFrameBus.cameraBitmap.get()?.let { camera ->
                paint.alpha = 255
                canvas.drawBitmap(camera, null, Rect(0, 0, width, height), paint)
            }
        }

        val screen = VrFrameBus.screenBitmap.get()
        if (screen == null) {
            paint.alpha = 255
            paint.color = Color.WHITE
            paint.textSize = 42f
            canvas.drawText("VrPhone: нажмите Старт", 48f, height / 2f, paint)
            return
        }

        paint.alpha = if (VrFrameBus.mode.get() == VrMode.AR) VrFrameBus.overlayAlpha.get() else 255
        drawSideBySide(canvas, screen)
        paint.alpha = 255
    }

    private fun drawSideBySide(canvas: Canvas, bitmap: Bitmap) {
        val halfWidth = width / 2
        val leftEye = Rect(0, 0, halfWidth, height)
        val rightEye = Rect(halfWidth, 0, width, height)

        canvas.save()
        canvas.clipRect(leftEye)
        canvas.drawBitmap(bitmap, null, leftEye, paint)
        canvas.restore()

        canvas.save()
        canvas.clipRect(rightEye)
        canvas.drawBitmap(bitmap, null, rightEye, paint)
        canvas.restore()
    }
}
