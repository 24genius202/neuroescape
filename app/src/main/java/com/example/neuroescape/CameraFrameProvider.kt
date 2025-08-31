package com.example.neuroescape

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import androidx.camera.camera2.interop.Camera2Interop
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
    private var camera: Camera? = null
    private var bitmapsize: Pair<Int, Int> = Pair(640, 640)

    @SuppressLint("UnsafeOptInUsageError")
    fun startCamera(imageView: ImageView, imageVeiw2: ImageView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview는 생략, 그냥 ImageAnalysis로 처리
            val imageAnalysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(resolution, resolution))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            val extender = Camera2Interop.Extender(imageAnalysisBuilder)
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
            )
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY,
                500
            )
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                5_000_000L
            )
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
            )

            val imageAnalysis = imageAnalysisBuilder.build().also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    latestImageProxy?.close()
                    latestImageProxy = imageProxy

                    // ImageProxy -> Bitmap 변환
                    val bitmap = imageProxy.toBitmap()
                    bitmapsize = Pair(bitmap.width, bitmap.height)

                    val bitmap1 = TfliteRunner.getframe()

                    // UI 스레드에서 ImageView 업데이트
                    (context as? LifecycleOwner)?.let { owner ->
                        (imageView.context as? android.app.Activity)?.runOnUiThread {
                            imageView.setImageBitmap(bitmap1)
                        }
                    }

                    (context as? LifecycleOwner)?.let { owner ->
                        (imageVeiw2.context as? android.app.Activity)?.runOnUiThread {
                            imageVeiw2.setImageBitmap(TfliteRunner.rotateCW(bitmap))
                        }
                    }

                    frameListener?.invoke(imageProxy)
                }
            }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalysis
                )
                Log.d("CameraFrameProvider", "Camera started successfully")
            } catch (exc: Exception) {
                Log.e("CameraFrameProvider", "Camera binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun getbitmapsize(): Pair<Int, Int>{return bitmapsize}

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    fun getLatestFrame(): ImageProxy? {
        return latestImageProxy
    }

    fun enableflash(boolean: Boolean){
        camera?.cameraControl?.enableTorch(boolean)
    }
}