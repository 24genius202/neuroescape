package com.example.neuroescape

import android.content.Context
import android.graphics.*
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import android.util.Log
import android.view.ViewGroup
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
import androidx.lifecycle.LifecycleOwner

val CLASS_NAMES = listOf("Leverhandle", "Pushbarhandle", "Roundhandle", "Exit", "Fire", "Handrail")

object TfliteRunner : ImageAnalysis.Analyzer {

    private lateinit var interpreter: Interpreter
    private var modelInputWidth: Int = 0
    private var modelInputHeight: Int = 0
    private var modelInputMax: Int = 0
    private var inputbitmap: Bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    private var inputbitmapsize: InputBitmapSize = InputBitmapSize(0,0,0,0,1f)
    private lateinit var cameraframeprovider: CameraFrameProvider



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

    fun runcycle(image: ImageProxy): List<Detection> {
        Log.d("DEBUGLOG", "[TfliteRunner]runcycle")
        analyze(image)
        return latestDetections
    }

    override fun analyze(image: ImageProxy) {
        Log.d("DEBUGLOG", "[TfliteRunner]analyze")
        // Bitmap 변환
        val bitmap = rotateCW(image.toBitmap())
        image.close() // 변환 후 바로 close

        //비트맵 변환 -> Rotate -> Crop -> letterbox

        val preprocess = cropBitmap(bitmap)
        val resizedvalue = resize(preprocess.bitmap)

        //TODO
        // ##########################################################
        // 아래 변수는 MainActivity에서 후처리(finalpostprocess)를 위한 코드인데
        // MainActivity의 후처리는 TfliteRunner에서 처리하게 변경 필요
        // ##########################################################
        inputbitmapsize = InputBitmapSize(preprocess.centerX, preprocess.centerY, preprocess.width, preprocess.height, resizedvalue.second)

        val croppedBitmap = resizedvalue.first
        inputbitmap = croppedBitmap
        croppedBitmap.let {
            //val (paddedBitmap, padding) = letterbox(it, modelInputWidth, modelInputHeight)
            val inputBuffer = convertBitmapToBuffer(it)

            val output = Array(1) { Array(10) { FloatArray(8400) } }
            interpreter.run(inputBuffer, output)

            latestDetections = postProcess(output[0])
        }
    }

    fun getCropBitmap(): Bitmap{return inputbitmap}
    fun getinputsize(): InputBitmapSize{return inputbitmapsize}

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

        return nonMaxSuppression(detections, iouThreshold)
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

    //TODO
    // ##########################################################
    // 개인적으론 cropBitmap 함수와 합치는걸 추천
    // ##########################################################
    private fun resize(bitmap: Bitmap): Pair<Bitmap, Float> {
        val targetSize = modelInputMax

        val originalWidth = bitmap.width
        //val originalHeight = bitmap.height

        // 배율 계산
        val scaleX = targetSize / originalWidth.toFloat()
        //val scaleY = targetSize.toFloat() / originalHeight.toFloat()

        // 리사이즈
        val resizedBitmap = bitmap.scale(targetSize, targetSize)

        return Pair(resizedBitmap, scaleX)
    }

    private fun cropBitmap(bitmap: Bitmap): CroppedBitmapResult {
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

        //TODO
        // ##########################################################
        // 선택된 영역이 없을 경우
        // 리턴값을 false로 설정하는 등 조치를 취해서
        // 이후 연산을 하지 않게 처리
        // ##########################################################

        // 선택된 영역이 없는 경우
        if (minX > maxX || minY > maxY) {
            return CroppedBitmapResult(
                createBitmap(1, 1),
                0, 0, 0, 0
            )
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

        minX = maxOf(0, minX - expandLeft)
        maxX = minOf(width - 1, maxX + expandRight)
        minY = maxOf(0, minY - expandTop)
        maxY = minOf(height - 1, maxY + expandBottom)

        croppedWidth = maxX - minX + 1
        croppedHeight = maxY - minY + 1

        // 잘라낸 정사각형 비트맵 생성
        val croppedBitmap = createBitmap(croppedWidth, croppedHeight)
        for (y in 0 until croppedHeight) {
            for (x in 0 until croppedWidth) {
                val color = bitmap[minX + x, minY + y]
                croppedBitmap[x, y] = color
            }
        }

        //TODO
        // ##########################################################
        // crop한 비트맵이 원본 이미지를 벗어나면
        // padding을 해서 정사각형으로 만들어야함
        // ##########################################################


        // 중앙 좌표 계산 (원본 기준, 픽셀 단위)
        val centerX = minX + croppedWidth / 2
        val centerY = minY + croppedHeight / 2

        return CroppedBitmapResult(croppedBitmap, centerX, centerY, croppedWidth, croppedHeight)
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

    //    private fun brightnessup(bitmap: Bitmap): Bitmap{
//        val width = bitmap.width
//        val height = bitmap.height
//
//        val newBitmap = bitmap.copy(bitmap.config, true) // 수정 가능한 복사본 생성
//
//        for (y in 0 until height) {
//            for (x in 0 until width) {
//                val pixel = bitmap.getPixel(x, y)
//
//                val alpha = Color.alpha(pixel)
//                val red = (Color.red(pixel) + 30).coerceIn(0, 255)
//                val green = (Color.green(pixel) + 30).coerceIn(0, 255)
//                val blue = (Color.blue(pixel) + 30).coerceIn(0, 255)
//
//                newBitmap.setPixel(x, y, Color.argb(alpha, red, green, blue))
//            }
//        }
//
//        return newBitmap
//    }


    //TODO
    // ##########################################################
    // 아래 후처리는 TfliteRunner로 이동해서 처리하게 변경 필요
    // ##########################################################
    fun finalpostprocess(result: List<Detection>): List<Detection>{
        val newdetections = mutableListOf<Detection>()

        val originalbitmapwidth = cameraframeprovider.getbitmapsize().first
        val croppedbitmapwidth = getinputsize().beforewidth
        val croppedbitmapx = getinputsize().beforeheight

        val cropscale = getinputsize().scale

        val croppedbitmapx1 = (croppedbitmapx-croppedbitmapwidth/2).toInt()

        Log.d("DEBUGLOG", "[MainActivity]originalbitmapwidth:$originalbitmapwidth croppedbitmapwidth:$croppedbitmapwidth croppedbitmapx:$croppedbitmapx")

        //crop 기준 픽셀 x 구하기 -> 기존으로 재스케일링 -> 원본 사진에서 픽셀 x 구하기 -> 그걸 상대 위치로 변환

        for(i in result){
            val absboxx = (i.box.x * croppedbitmapwidth).toInt()
            //절대 x 구하기
            var absolutex: Float

            var bboxabsx = absboxx + croppedbitmapx1

            //리스케일링
            bboxabsx = (bboxabsx / cropscale).toInt()

            //절대 위치들을 상대 위치로 변경
            absolutex = bboxabsx / originalbitmapwidth.toFloat()

            Log.d("DEBUGLOG", "[MainActivity]bboxabsx:$bboxabsx absolutex:$absolutex")

            val newbox = Box(absolutex, i.box.y, i.box.width, i.box.height)

            newdetections.add(Detection(i.classId, i.confidence, newbox))

            //debuginfo = DebugInfo(originalbitmapwidth, 0, croppedbitmapwidth, 0, 0f, bboxabsx, absolutex)
        }
        return newdetections
    }

    fun setupcfp(context: Context, perviewcontainer: ViewGroup, lifecycleOwner: LifecycleOwner){ cameraframeprovider = CameraFrameProvider(context, perviewcontainer, lifecycleOwner) }

}