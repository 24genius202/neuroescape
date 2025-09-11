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
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set

object TfliteRunner : ImageAnalysis.Analyzer {

    private lateinit var interpreter: Interpreter
    private var modelInputWidth: Int = 0
    private var modelInputHeight: Int = 0
    private var modelInputMax: Int = 0
    private var frameBitmap: Bitmap = createBitmap(1,1)
    private var cropBitmapSize: BitmapSize = BitmapSize(0,0,0,0,1f)

    // temp variable
    private lateinit var frameSize: Pair<Int, Int>



    // private set: getter-> public, setter ->private
    var latestDetections: List<Detection> = emptyList()

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

    fun runcycle(image: ImageProxy): List<Detection> {
        Log.d("DEBUGLOG", "[TfliteRunner]runcycle")
        analyze(image)
        return latestDetections
    }

    override fun analyze(image: ImageProxy) {
        Log.d("DEBUGLOG", "[TfliteRunner]analyze")
        // Bitmap 변환
        val bitmap = rotateCW(image.toBitmap())
        frameBitmap = bitmap
        frameSize = Pair(bitmap.width, bitmap.height)
        image.close() // 변환 후 바로 close

        val inputBitmap = cropBitmap(bitmap) ?: return
        inputBitmap.let {
            val inputBuffer = convertBitmapToBuffer(it)

            val output = Array(1) { Array(10) { FloatArray(8400) } }
            interpreter.run(inputBuffer, output)
            latestDetections = postProcess(output[0])
        }
    }
    fun rotateCW(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(90f) // 90도 회전

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true // 필터링 여부, true면 품질 향상
        )
    }
    fun getFrameBitmap(): Bitmap{return frameBitmap}


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

    private fun cropBitmap(bitmap: Bitmap): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0

        // 밝은 픽셀만 선택
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)

                if (red+ green + blue > 150) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }


        // 선택된 영역이 없는 경우
        if (minX > maxX || minY > maxY) {
            return null
        }


        // 현재 크기
        var croppedWidth = maxX - minX + 1
        var croppedHeight = maxY - minY + 1

        // 정사각형 변의 길이 결정
        val maxSide = maxOf(croppedWidth, croppedHeight)

        // 부족한 부분 확장
        val diffX = maxSide - croppedWidth
        val diffY = maxSide - croppedHeight

        val expandLeft = diffX / 2
        val expandRight = diffX - expandLeft
        val expandTop = diffY / 2
        val expandBottom = diffY - expandTop

        var paddingleft = 0
        var paddingRight = 0
        var paddingTop = 0
        var paddingBottom = 0

        //maxY + expandBottom >= height maxX + expandRight >= width minY - expandTop < 0 minX - expandLeft < 0

        //프레임 밖으로 나가는 변에 패딩 얼마나 추가할지 결정
        if(maxY + expandBottom >= height) paddingBottom = maxY + expandBottom - height //1 더해야 하나?
        if(maxX + expandRight >= width) paddingRight = maxX + expandRight - width
        if(minY - expandTop < 0) paddingTop = expandTop - minY
        if(minX - expandLeft < 0) paddingleft = expandLeft - minX

        minX = maxOf(0, minX - expandLeft)
        maxX = minOf(width - 1, maxX + expandRight)
        minY = maxOf(0, minY - expandTop)
        maxY = minOf(height - 1, maxY + expandBottom)

        croppedWidth = maxX - minX + 1
        croppedHeight = maxY - minY + 1

        // 잘라낸 정사각형 비트맵 생성
        val outWidth = croppedWidth + paddingleft + paddingRight
        val outHeight = croppedHeight + paddingTop + paddingBottom
        val outMax = max(outWidth, outHeight)
        val croppedBitmap = createBitmap(outMax, outMax)

        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                // crop 내부 좌표
                val inCropX = x - paddingleft
                val inCropY = y - paddingTop

                if (inCropX !in 0 until croppedWidth || inCropY !in 0 until croppedHeight) {
                    // padding 영역 → 단색 채움
                    croppedBitmap[x, y] = 0xFF404040.toInt()
                } else {
                    // 원본 좌표 매핑
                    val srcX = minX + inCropX
                    val srcY = minY + inCropY

                    // 원본 픽셀 가져오기
                    val color = bitmap[srcX, srcY]
                    croppedBitmap[x, y] = color
                }
            }
        }

        // 중앙 좌표 계산 (원본 기준, 픽셀 단위)
        val centerX = minX + croppedWidth / 2
        val centerY = minY + croppedHeight / 2


        // 배율 계산
        val scaleX = modelInputMax / outWidth.toFloat()

        val resizedBitmap = croppedBitmap.scale(modelInputMax, modelInputMax)
        Pair(resizedBitmap, scaleX)

        cropBitmapSize = BitmapSize(minX-paddingleft, minY-paddingTop, outMax, outMax, scaleX)

        return resizedBitmap
    }



    private fun convertBitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(1 * modelInputMax * modelInputMax * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(modelInputMax * modelInputMax)
        bitmap.getPixels(intValues, 0, modelInputMax, 0, 0, modelInputMax, modelInputMax)


        for (pixel in intValues) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)    // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)     // G
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)             // B
        }
        inputBuffer.rewind()
        return inputBuffer
    }

    private fun postProcess(output: Array<FloatArray>): List<Detection> {
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

                //최종 반환 타입
                val box = Box(
                    x = x - w / 2,
                    y = y - h / 2,
                    width = w,
                    height = h
                )

                detections.add(Detection(classId, maxScore, box))
            }
        }

        val result = nonMaxSuppression(detections, iouThreshold)
        val newdetections = mutableListOf<Detection>()
        val originalBitmapWidth = frameSize.first
        val originalBitmapHeight = frameSize.second



        Log.d("DEBUGLOG", "[TfliteRunner]frame:$frameSize crop:$cropBitmapSize")

        //crop 기준 픽셀 x 구하기 -> 기존으로 재스케일링 -> 원본 사진에서 픽셀 x 구하기 -> 그걸 상대 위치로 변환

        for(i in result){
            Log.d("DEBUGLOG", "[TfliteRunner]newbox: $cropBitmapSize")
            val originalPositionX = (i.box.x * cropBitmapSize.beforewidth).toInt() + (cropBitmapSize.X).toInt()
            val originalPositionY = (i.box.y * cropBitmapSize.beforeheight).toInt() + (cropBitmapSize.Y).toInt()

            //절대 위치들을 상대 위치로 변경
            val absoluteX: Float = originalPositionX / originalBitmapWidth.toFloat()
            val absoluteY: Float = originalPositionY / originalBitmapHeight.toFloat()

            val newbox = Box(absoluteX, absoluteY, i.box.width*cropBitmapSize.beforewidth / originalBitmapWidth.toFloat(), i.box.height*cropBitmapSize.beforeheight / originalBitmapHeight.toFloat())
            Log.d("DEBUGLOG", "[TfliteRunner]newbox: $newbox")
            newdetections.add(Detection(i.classId, i.confidence, newbox))

        }
        return newdetections
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

}