package com.example.neuroescape

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFrameProvider(
    private val context: Context,
    private val previewViewContainer: ViewGroup,
    private val lifecycleOwner: LifecycleOwner,
    private val resolution: Int = 640,
    private val frameListener: ((ImageProxy) -> Unit)? = null
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var latestImageProxy: ImageProxy? = null

    @SuppressLint("UnsafeOptInUsageError")
    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    val previewView = previewViewContainer.getChildAt(0) as? androidx.camera.view.PreviewView
                    if (previewView == null) {
                        Log.e("CameraFrameProvider", "PreviewView not found as first child.")
                        return@addListener
                    }
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(resolution, resolution))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        latestImageProxy?.close()
                        latestImageProxy = imageProxy
                        frameListener?.invoke(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                Log.d("CameraFrameProvider", "Camera started successfully")
            } catch (exc: Exception) {
                Log.e("CameraFrameProvider", "Camera binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    fun getLatestFrame(): ImageProxy? {
        return latestImageProxy
    }
}