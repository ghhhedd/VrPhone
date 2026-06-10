package com.vrphone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var startStopButton: Button
    private lateinit var switchModeButton: Button
    private lateinit var opacitySlider: SeekBar
    private lateinit var opacityLabel: TextView
    private lateinit var projectionManager: MediaProjectionManager

    private var isRunning = false
    private var currentMode = VrMode.CINEMA
    private var projectionResultCode = 0
    private var projectionResultData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startStopButton = findViewById(R.id.startStopButton)
        switchModeButton = findViewById(R.id.switchModeButton)
        opacitySlider = findViewById(R.id.opacitySlider)
        opacityLabel = findViewById(R.id.opacityLabel)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        startStopButton.setOnClickListener {
            if (isRunning) stopCurrentService() else startSelectedMode()
        }

        switchModeButton.setOnClickListener {
            currentMode = if (currentMode == VrMode.CINEMA) VrMode.AR else VrMode.CINEMA
            VrFrameBus.mode.set(currentMode)
            updateControls()
            if (isRunning) {
                stopServicesOnly()
                startSelectedMode()
            }
        }

        opacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                VrFrameBus.overlayAlpha.set((progress * 255) / 100)
                opacityLabel.text = "Прозрачность: $progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        updateControls()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            projectionResultCode = resultCode
            projectionResultData = data
            launchServiceWithProjection()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RUNTIME_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startSelectedMode()
        }
    }

    override fun onDestroy() {
        stopCurrentService()
        super.onDestroy()
    }

    private fun startSelectedMode() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
            return
        }

        if (projectionResultData == null) {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            return
        }

        launchServiceWithProjection()
    }

    private fun launchServiceWithProjection() {
        val data = projectionResultData ?: return
        val serviceIntent = when (currentMode) {
            VrMode.CINEMA -> ScreenCaptureService.intent(this, projectionResultCode, data)
            VrMode.AR -> CameraCaptureService.intent(this, projectionResultCode, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        isRunning = true
        updateControls()
    }

    private fun stopCurrentService() {
        stopServicesOnly()
        isRunning = false
        VrFrameBus.clear()
        updateControls()
    }

    private fun stopServicesOnly() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopService(Intent(this, CameraCaptureService::class.java))
    }

    private fun hasRequiredPermissions(): Boolean {
        val cameraGranted = currentMode != VrMode.AR ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return cameraGranted && notificationsGranted
    }

    private fun requestRequiredPermissions() {
        val permissions = buildList {
            if (currentMode == VrMode.AR) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        ActivityCompat.requestPermissions(this, permissions, REQUEST_RUNTIME_PERMISSIONS)
    }

    private fun updateControls() {
        startStopButton.text = if (isRunning) "Стоп" else "Старт"
        switchModeButton.text = when (currentMode) {
            VrMode.CINEMA -> "Режим: Кинотеатр"
            VrMode.AR -> "Режим: AR"
        }
        opacitySlider.isEnabled = currentMode == VrMode.AR
        opacityLabel.alpha = if (currentMode == VrMode.AR) 1f else 0.45f
    }

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 10
        private const val REQUEST_RUNTIME_PERMISSIONS = 11
    }
}
