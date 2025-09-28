package com.example.neuroescape

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.graphics.createBitmap

class CameraFrameProvider(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val frameListener: ((ImageProxy) -> Unit)? = null
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var latestImageProxy: ImageProxy? = null
    private var camera: Camera? = null
    private var windowtype = false
    private var isRotate: Boolean = true

    private var boxBitmap : Bitmap = createBitmap(1,1)

    @SuppressLint("UnsafeOptInUsageError")
    fun startCamera(imageView: ImageView, originalbutton: Button, cropbutton: Button) {
        Log.d("DEBUGLOG", "[CameraFrameProvider]startCamera")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)


        val delayMillis = 1000L
        originalbutton.postDelayed({
            originalbutton.setOnClickListener {
                windowtype = true
            }
            cropbutton.setOnClickListener {
                windowtype = false
            }
        }, delayMillis)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 해상도 설정
            val displayMetrics = context.resources.displayMetrics
            val isPortrait = displayMetrics.heightPixels > displayMetrics.widthPixels

            val imageAnalysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(
                    if (isPortrait) android.util.Size(1080, 1920)
                    else android.util.Size(1920, 1080)
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            val extender = Camera2Interop.Extender(imageAnalysisBuilder)
            // 자동 노출 끄기
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
            )
            // iso값 고정
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY,
                500
            )
            // 노출시간 설정
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                5_000_000L
            )
            // 형광등 화이트밸런스 적용
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

                    // UI 스레드에서 ImageView 업데이트
                    (context as? LifecycleOwner)?.let {
                        (imageView.context as? android.app.Activity)?.runOnUiThread {
                            imageView.setBackgroundColor(android.graphics.Color.BLACK)

                            val parent = imageView.parent as FrameLayout
                            val layoutParams = imageView.layoutParams

                            if (windowtype) {
                                val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
                                if (!isRotate) {
                                    isRotate = true
                                    val layoutParams = imageView.layoutParams
                                    if (rotation == 90f || rotation == 270f) {
                                        layoutParams.width = parent.height
                                        layoutParams.height = parent.width
                                    } else {
                                        layoutParams.width = parent.width
                                        layoutParams.height = parent.height
                                    }
                                    imageView.layoutParams = layoutParams

                                    imageView.pivotX = 0f
                                    imageView.pivotY = 0f
                                    imageView.rotation = rotation

                                    when (rotation) {
                                        90f -> {
                                            imageView.translationX = parent.width.toFloat()
                                            imageView.translationY = 0f
                                        }
                                        180f -> {
                                            imageView.translationX = parent.width.toFloat()
                                            imageView.translationY = parent.height.toFloat()
                                        }
                                        270f -> {
                                            imageView.translationX = 0f
                                            imageView.translationY = parent.height.toFloat()
                                        }
                                        else -> {
                                            imageView.translationX = 0f
                                            imageView.translationY = 0f
                                        }
                                    }
                                }
                                imageView.setImageBitmap(bitmap)

                            }
                            else {
                                if (isRotate) {
                                    // boxBitmap: 회전 적용 X
                                    layoutParams.width = parent.width
                                    layoutParams.height = parent.height
                                    imageView.layoutParams = layoutParams

                                    imageView.rotation = 0f
                                    imageView.translationX = 0f
                                    imageView.translationY = 0f
                                }
                                imageView.setImageBitmap(boxBitmap)
                                isRotate = false
                            }
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


    fun shutdown() {
        cameraExecutor.shutdown()
    }

    fun getLatestFrame(): ImageProxy? {
        return latestImageProxy
    }

    fun enableflash(boolean: Boolean){
        camera?.cameraControl?.enableTorch(boolean)
    }

    fun setBoxBitmap(bitmap: Bitmap) {
        boxBitmap  = bitmap
    }
}