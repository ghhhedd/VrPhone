package com.vrphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class CameraCaptureService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var captureJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopProjectionResources()
        }
    }
    private var cameraProvider: ProcessCameraProvider? = null

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("AR-режим активен"))
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        VrFrameBus.mode.set(VrMode.AR)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startProjection(resultCode, resultData)
        startCamera()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCamera()
        stopProjection()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        imageProxy.use { proxy ->
                            proxy.toBitmap()?.let(VrFrameBus::setCameraFrame)
                        }
                    }
                }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
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
            "VrPhoneArScreen",
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
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("VrPhone")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun ImageProxy.toBitmap(): Bitmap? {
        if (format != ImageFormat.YUV_420_888) return null
        val nv21 = image?.toNv21() ?: return null
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val output = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 75, output)
        return BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size())
    }

    private fun Image.toNv21(): ByteArray {
        val frameSize = width * height
        val nv21 = ByteArray(frameSize + frameSize / 2)

        copyPlane(planes[0], width, height, nv21, 0, 1)
        copyPlane(planes[2], width / 2, height / 2, nv21, frameSize, 2)
        copyPlane(planes[1], width / 2, height / 2, nv21, frameSize + 1, 2)

        return nv21
    }

    private fun copyPlane(
        plane: Image.Plane,
        planeWidth: Int,
        planeHeight: Int,
        output: ByteArray,
        outputOffset: Int,
        outputPixelStride: Int
    ) {
        val buffer = plane.buffer.duplicate()
        var outputIndex = outputOffset
        val row = ByteArray(plane.rowStride)
        for (rowIndex in 0 until planeHeight) {
            val bytesPerRow = if (plane.pixelStride == 1 && outputPixelStride == 1) planeWidth else (planeWidth - 1) * plane.pixelStride + 1
            buffer.position(rowIndex * plane.rowStride)
            buffer.get(row, 0, bytesPerRow)
            for (columnIndex in 0 until planeWidth) {
                output[outputIndex] = row[columnIndex * plane.pixelStride]
                outputIndex += outputPixelStride
            }
        }
    }

    private inline fun ImageProxy.use(block: (ImageProxy) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "com.vrphone.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.vrphone.RESULT_DATA"
        private const val CHANNEL_ID = "vrphone_capture"
        private const val NOTIFICATION_ID = 1002

        fun intent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, CameraCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
    }
}
