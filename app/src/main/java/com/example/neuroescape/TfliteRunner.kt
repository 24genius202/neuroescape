package com.example.neuroescape

import android.content.Context
import android.graphics.*
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

val CLASS_NAMES = listOf("Leverhandle", "Pushbarhandle", "Roundhandle", "Exit", "Fire", "Handrail")

object TfliteRunner : ImageAnalysis.Analyzer {

    private lateinit var interpreter: Interpreter
    private var modelInputWidth: Int = 0
    private var modelInputHeight: Int = 0
    private var modelInputMax: Int = 0




    // private set: getter-> public, setter ->private
    var latestDetections: List<Detection> = emptyList()
        private set

    private const val confidenceThreshold = 0.5f
    private const val iouThreshold = 0.45f

    fun initialize(context: Context) {
        Log.d("DEBUGLOG", "[TfliteRunner]initialize")

        // load model
        val modelFile = context.assets.openFd("neuroescape.tflite")
        val modelBuffer = modelFile.createInputStream().channel.map(
            FileChannel.MapMode.READ_ONLY,
            modelFile.startOffset,
            modelFile.declaredLength
        )

        // use gpu
        try {
            Log.d("DEBUGLOG", "[TfliteRunner]use gpu")
            val gpu = GpuDelegate()
            val gpuOptions = Interpreter.Options()
            gpuOptions.addDelegate(gpu)
            gpuOptions.setNumThreads(4)

            // set interpreter
            interpreter = Interpreter(modelBuffer, gpuOptions)
        }
        // use cpu
        catch (e: Exception) {
            Log.d("DEBUGLOG", "[TfliteRunner]use cpu")
            val cpuOptions = Interpreter.Options().setNumThreads(4)

            // set interpreter
            interpreter = Interpreter(modelBuffer, cpuOptions)
        }

        // init model input h,w
        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()  // [1, height, width, channels]
        modelInputWidth = inputShape[2]
        modelInputHeight = inputShape[1]
        modelInputMax = max(modelInputWidth, modelInputHeight)



    }

    fun runcycle(image: ImageProxy, context: Context): List<Detection> {
        Log.d("DEBUGLOG", "[TfliteRunner]runcycle")
        analyze(image)
        return latestDetections
    }

    override fun analyze(image: ImageProxy) {
        Log.d("DEBUGLOG", "[TfliteRunner]analyze")
        // Bitmap 변환
        val bitmap = image.toBitmap()
        image.close() // 변환 후 바로 close

        bitmap.let {
            val (paddedBitmap, padding) = letterbox(it, modelInputWidth, modelInputHeight)
            val inputBuffer = convertBitmapToBuffer(paddedBitmap)

            val output = Array(1) { Array(10) { FloatArray(8400) } }
            interpreter.run(inputBuffer, output)

            latestDetections = postProcess(output[0], padding)
        }
    }

    private fun nonMaxSuppression(
        detections: List<Detection>,
        iouThreshold: Float
    ): List<Detection> {
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val finalDetections = mutableListOf<Detection>()
        val suppressed = BooleanArray(sortedDetections.size) { false }

        for (i in sortedDetections.indices) {
            if (suppressed[i]) continue
            finalDetections.add(sortedDetections[i])

            val boxA = RectF(
                sortedDetections[i].box.x,
                sortedDetections[i].box.y,
                sortedDetections[i].box.x + sortedDetections[i].box.width,
                sortedDetections[i].box.y + sortedDetections[i].box.height
            )

            for (j in i + 1 until sortedDetections.size) {
                if (suppressed[j]) continue
                val boxB = RectF(
                    sortedDetections[j].box.x,
                    sortedDetections[j].box.y,
                    sortedDetections[j].box.x + sortedDetections[j].box.width,
                    sortedDetections[j].box.y + sortedDetections[j].box.height
                )

                val iou = calculateIoU(boxA, boxB)
                if (iou > iouThreshold) suppressed[j] = true
            }
        }
        return finalDetections
    }

    private fun calculateIoU(boxA: RectF, boxB: RectF): Float {
        val intersection = RectF()
        intersection.set(
            max(boxA.left, boxB.left),
            max(boxA.top, boxB.top),
            min(boxA.right, boxB.right),
            min(boxA.bottom, boxB.bottom)
        )
        val intersectionArea = max(0f, intersection.width()) * max(0f, intersection.height())
        val boxAArea = boxA.width() * boxA.height()
        val boxBArea = boxB.width() * boxB.height()
        return intersectionArea / (boxAArea + boxBArea - intersectionArea)
    }

    private fun postProcess(output: Array<FloatArray>, padding: Pair<Float, Float>): List<Detection> {
        val detections = mutableListOf<Detection>()
        val numPredictions = output[0].size
        val numFeatures = output.size

        val transposedOutput = Array(numPredictions) { FloatArray(numFeatures) }
        for (i in 0 until numPredictions) {
            for (j in 0 until numFeatures) {
                transposedOutput[i][j] = output[j][i]
            }
        }

        for (pred in transposedOutput) {
            var maxScore = 0f
            var classId = -1
            for (j in 4 until pred.size) {
                if (pred[j] > maxScore) {
                    maxScore = pred[j]
                    classId = j - 4
                }
            }

            if (maxScore > confidenceThreshold) {
                val x = pred[0]
                val y = pred[1]
                val w = pred[2]
                val h = pred[3]

                val padX = padding.first * modelInputMax
                val padY = padding.second * modelInputMax
                val scale = modelInputMax.toFloat() / (modelInputMax - 2 * padX)

                val scaledX = (x - padX) * scale
                val scaledY = (y - padY) * scale
                val scaledW = w * scale
                val scaledH = h * scale


                //최종 반환 타입
                val box = Box(
                    x = scaledX - scaledW / 2,
                    y = scaledY - scaledH / 2,
                    width = scaledW,
                    height = scaledH
                )

                detections.add(Detection(classId, maxScore, box))
            }
        }

        return nonMaxSuppression(detections, iouThreshold)
    }

    private fun letterbox(bitmap: Bitmap, modelInputWidth: Int, modelInputHeight: Int): Pair<Bitmap, Pair<Float, Float>> {
        Log.d("DEBUGLOG", "[TfliteRunner]letterbox")

        // w, h
        val imgWidth = bitmap.width
        val imgHeight = bitmap.height

        val ratio = min(modelInputWidth.toFloat() / imgWidth, modelInputHeight.toFloat() / imgHeight)

        val unpadWidth = (imgWidth * ratio).toInt()
        val unpadHeight = (imgHeight * ratio).toInt()

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, unpadWidth, unpadHeight, true)
        val dw = (modelInputWidth - unpadWidth) / 2
        val dh = (modelInputHeight - unpadHeight) / 2


        val paddedBitmap = Bitmap.createBitmap(modelInputWidth, modelInputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(paddedBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))


        val destRect = Rect(dw, dh, dw + unpadWidth, dh + unpadHeight)
        canvas.drawBitmap(resizedBitmap, null, destRect, null)

        val padX = dw.toFloat() / modelInputWidth
        val padY = dh.toFloat() / modelInputHeight
        return Pair(paddedBitmap, Pair(padX, padY))
    }


    private fun convertBitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(1 * modelInputWidth * modelInputHeight * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(modelInputWidth * modelInputHeight)
        bitmap.getPixels(intValues, 0, modelInputWidth, 0, 0, modelInputWidth, modelInputHeight)


        for (pixel in intValues) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)    // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)     // G
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)             // B
        }
        inputBuffer.rewind()
        return inputBuffer
    }

}