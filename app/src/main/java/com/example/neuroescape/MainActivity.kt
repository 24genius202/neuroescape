package com.example.neuroescape

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageProxy
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.neuroescape.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import kotlin.math.abs

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraFrameProvider: CameraFrameProvider
    private lateinit var tfrunner: TfliteRunner



    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val AI_PROCESS_INTERVAL_MS = 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("DEBUGLOG", "[MainActivity]onCreate")
        super.onCreate(savedInstanceState)

        // 권한 체크
        if (!checkPermission()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        Log.d("DEBUGLOG", "[MainActivity]initialize")
        // tflite initialize
        tfrunner = TfliteRunner
        tfrunner.initialize(this)
        // layout initialize
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        findViewById<TextView>(R.id.VersionView).text = "v1.0"
        // camera initialize
        cameraFrameProvider = CameraFrameProvider(this, binding.previewContainer as ViewGroup, this)
        cameraFrameProvider.startCamera()


        Log.d("DEBUGLOG", "[MainActivity]Vibration corutine start")
        //진동 안내 시작
        lifecycleScope.launch(Dispatchers.Default) {
            VibrationGuide.startvibratorguide(this@MainActivity, lifecycleScope)
        }

        Log.d("DEBUGLOG", "[MainActivity]tflite corutine start")
        // tflite 물체 인식 시작
        lifecycleScope.launch {
            delay(2000) // 2초 대기
            tflitePolling()
        }
    }



    private fun tflitePolling() {
        Log.d("DEBUGLOG", "[MainActivity]tflitePolling")
        lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) { // 코루틴이 살아있는 동안 반복
                val resultDetected = maincode() // AI 처리 (백그라운드)

//                // UI 업데이트가 필요하면 Main 스레드로 전환
//                if (resultDetected) {
//                    withContext(Dispatchers.Main) {
//                        // 예시: 화면에 결과 표시
//                        binding.resultTextView.text = "물체 발견!"
//                    }
//                }

                delay(AI_PROCESS_INTERVAL_MS) // 다음 주기까지 대기
            }
        }
    }

    private fun maincode(): Boolean {
        Log.d("DEBUGLOG", "[MainActivity]maincode")

        // get frame
        val frame: ImageProxy? = cameraFrameProvider.getLatestFrame()
        if (frame == null) {
            Log.d("DEBUGLOG", "[MainActivity]get frame fail")
            return false
        }

        //get tflite result
        val result: List<Detection> = try {
            tfrunner.runcycle(frame, this)
        } catch (e: Exception) {
            Log.e("DEBUGLOG", "[MainActivity]TFLite run failed", e)
            return false
        }
        Log.d("DEBUGLOG", "[MainActivity]"+result.toString())

        for(i in result){
            Log.d("DEBUGLOG", "[MainActivity]" + " ㄴ " + i.toString())
            //언패킹 작업
            val classid: Int = i.classId
            val classconfidence: Float = i.confidence
            val box: Box = i.box

            val x = (box.x * 640).toInt()
            val y = (box.y * 640).toInt()
            val w = (box.width * 640).toInt()
            val h = (box.height * 640).toInt()

            Log.d("DEBUGLOG", "[MainActivity] x:$x y:$y w:$w h:$h")

            val referencepos: Float

            val context: Context = this

            //if(classid != 3) VibrationGuide.updatevibrator(0f, 100F)

            when(classid){
                //진동 안내 실행

                //비상구
                3 -> {referencepos = abs(320 - x.toFloat()) / 640
                    //.let { BigDecimal(it.toDouble()).setScale(6, RoundingMode.HALF_UP).toFloat() }
                    Log.d("DEBUGLOG", "[MainActivity] referencepos:$referencepos")
                    //referencepos 정상적으로 나옴
                    VibrationGuide.updatevibrator(referencepos, 100F)}
                // 손잡이
                0 -> { lifecycleScope.launch(Dispatchers.Default) {
                    VoiceGuide.voiceguide(context, 2)
                }}
                1 -> { lifecycleScope.launch(Dispatchers.Default) {
                    VoiceGuide.voiceguide(context, 3)
                }}
                2 -> { lifecycleScope.launch(Dispatchers.Default) {
                    VoiceGuide.voiceguide(context, 4)
                }}
                // 난간
                5 -> { lifecycleScope.launch(Dispatchers.Default) {
                    VoiceGuide.voiceguide(context, 1)
                }}
            }
        }

        return true
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