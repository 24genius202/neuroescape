package com.example.neuroescape

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.neuroescape.databinding.ActivityMainBinding
import kotlinx.coroutines.*

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraFrameProvider: CameraFrameProvider
    private lateinit var tfrunner: TfliteRunner
    private var lastExitDetectedTime: Long = 0

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val AI_PROCESS_INTERVAL_MS = 100L
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("DEBUGLOG", "[MainActivity]onCreate")
        super.onCreate(savedInstanceState)

        // Logo 표시
        Log.d("DEBUGLOG", "[MainActivity]show logo")
        setContentView(R.layout.logo)


        lifecycleScope.launch {
            // 로고 출력을 위해 2초 대기
            delay(2000)

            // Main 레이아웃 전환 및 코드 동작
            Log.d("DEBUGLOG", "[MainActivity]MainActivity coroutine start")

            // layout initialize
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 권한 체크
            if (!checkPermission()) {
                ActivityCompat.requestPermissions(this@MainActivity, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }

            // tflite initialize
            Log.d("DEBUGLOG", "[MainActivity]initialize")
            tfrunner = TfliteRunner
            tfrunner.initialize(this@MainActivity)

            // camera initialize
            cameraFrameProvider = CameraFrameProvider(this@MainActivity,this@MainActivity)
            cameraFrameProvider.startCamera(binding.cameraImage, binding.original, binding.crop)

            // set flash event listener
            binding.Flash.setOnCheckedChangeListener { _, isChecked ->
                Log.d("DEBUGLOG", "[MainActivity]flash $isChecked")
                cameraFrameProvider.enableflash(isChecked)
            }

            // 진동 안내 시작
            Log.d("DEBUGLOG", "[MainActivity]Vibration coroutine start")
            lifecycleScope.launch(Dispatchers.Default) {
                VibrationGuide.startvibratorguide(this@MainActivity, lifecycleScope)
            }

            // tflite 물체 인식 시작
            Log.d("DEBUGLOG", "[MainActivity]tflite coroutine start")
            lifecycleScope.launch {
                delay(2000) // 2초 대기
                lifecycleScope.launch(Dispatchers.Default) {
                    while (isActive) {
                        val result = tfLiteDetect()
                        Log.d("DEBUGLOG", "[MainActivity]"+result.toString())
                        detectProcess(result)
                        delay(AI_PROCESS_INTERVAL_MS)
                    }
                }
            }
        }
    }



    private fun tfLiteDetect(): List<Detection> {
        Log.d("DEBUGLOG", "[MainActivity]tfLiteDetect")

        // get frame
        val frame = cameraFrameProvider.getLatestFrame() ?: run {
            Log.d("DEBUGLOG", "[MainActivity]get frame fail")
            return emptyList<Detection>()
        }
        //get tflite result
        return try {
            tfrunner.runcycle(frame)
        } catch (e: Exception) {
            Log.e("DEBUGLOG", "[MainActivity]TFLite run failed", e)
            emptyList<Detection>()
        }
    }



    private fun detectProcess(result: List<Detection>) {
        Log.d("DEBUGLOG", "[MainActivity]detectProcess")
        var boxBitmap: Bitmap = TfliteRunner.getFrameBitmap()

        val exitDetected = result.any { it.classId == 3 }
        val currentTime = System.currentTimeMillis()

        // 비상구 일정시간 탐지 안되면 진동 off
        if (exitDetected) {
            lastExitDetectedTime = currentTime
            VibratorTimer.activate = true
        } else if (currentTime - lastExitDetectedTime >= 2 * AI_PROCESS_INTERVAL_MS) {
            VibratorTimer.activate = false
        }

        for(i in result){
            Log.d("DEBUGLOG", "[MainActivity]" + " ㄴ " + i.toString())
            val classid: Int = i.classId
            val box: Box = i.box

            boxBitmap = drawBoxOnBitmap(boxBitmap, i)

            val context: Context = this

            // class id 별 동작 수행
            when(classid){
                //비상구
                3 -> {
                    // 진동 안내
                    VibratorTimer.activate = true
                    Log.d("DEBUGLOG", "[MainActivity]middle x: ${box.x+(box.width/2)}")
                    VibrationGuide.updatevibrator(box.x+(box.width/2), box.width*box.height)
                }
                // 그 외
                else -> { lifecycleScope.launch(Dispatchers.Default) {
                    // 음성 안내
                    if(!VoiceGuide.isrunning()) VoiceGuide.voiceguide(context, classid)
                }}
            }
        }
        // boxBitmap 설정
        cameraFrameProvider.setBoxBitmap(boxBitmap)
    }



    private fun drawBoxOnBitmap(bitmap: Bitmap, detection: Detection) : Bitmap {
        Log.d("DEBUGLOG", "[MainActivity]drawBoxOnBitmap")
        // Canvas 객체 생성
        val canvas = Canvas(bitmap)

        // Paint 객체 정의
        val CLASS_NAMES = listOf("Leverhandle", "Pushbarhandle", "Roundhandle", "Exit", "Fire", "Handrail")
        val colorList = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW)
        val labelColor = colorList[detection.classId]

        // TFLite 상대 좌표 -> 픽셀 크기 변환
        val box = detection.box
        val leftPx = box.x * bitmap.width
        val topPx = box.y * bitmap.height
        val rightPx = (box.x + box.width) * bitmap.width
        val bottomPx = (box.y + box.height) * bitmap.height

        // RectF 사각형 좌표
        val rect = RectF(leftPx, topPx, rightPx, bottomPx)

        // 물체 사각형 윤곽
        val paint = Paint().apply {
            color = labelColor
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        canvas.drawRect(rect, paint)

        // 라벨 배경
        val labelBackgroundPaint = Paint().apply {
            color = labelColor
            style = Paint.Style.FILL
        }

        // 라벨 텍스트
        val labelTextPaint = Paint().apply {
            color = Color.BLACK
            textSize = 25f
            textAlign = Paint.Align.LEFT
        }

        val label = "${CLASS_NAMES[detection.classId]}: %.2f".format(detection.confidence)
        val bounds = Rect()
        labelTextPaint.getTextBounds(label, 0, label.length, bounds)

        // 라벨 배경 사각형의 좌표
        val labelRect = RectF(
            leftPx,
            topPx - bounds.height().toFloat() - 5f,
            leftPx + bounds.width().toFloat() + 10f,
            topPx
        )

        canvas.drawRect(labelRect, labelBackgroundPaint)
        canvas.drawText(label, leftPx + 5f, topPx - 5f, labelTextPaint)

        return bitmap
    }



    private fun checkPermission(): Boolean {
        Log.d("DEBUGLOG", "[MainActivity]checkPermission")
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        if (::cameraFrameProvider.isInitialized) {
            cameraFrameProvider.shutdown()
        }
    }
}