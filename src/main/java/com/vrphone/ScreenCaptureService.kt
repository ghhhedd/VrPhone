package com.vrphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopProjectionResources()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Кинотеатр активен"))
        VrFrameBus.mode.set(VrMode.CINEMA)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startProjection(resultCode, resultData)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProjection()
        super.onDestroy()
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        stopProjection()
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "VrPhoneScreen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        captureJob = serviceScope.launch {
            while (true) {
                readLatestScreenFrame(width, height)
                delay(33L)
            }
        }
    }

    private fun readLatestScreenFrame(width: Int, height: Int) {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val plane = image.planes.first()
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * width
            val bitmapWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            VrFrameBus.setScreenFrame(Bitmap.createBitmap(bitmap, 0, 0, width, height))
            bitmap.recycle()
        } finally {
            image.close()
        }
    }

    private fun stopProjection() {
        val projection = mediaProjection
        stopProjectionResources()
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
    }

    private fun stopProjectionResources() {
        captureJob?.cancel()
        captureJob = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection = null
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VrPhone", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("VrPhone")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "com.vrphone.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.vrphone.RESULT_DATA"
        private const val CHANNEL_ID = "vrphone_capture"
        private const val NOTIFICATION_ID = 1001

        fun intent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
    }
}
