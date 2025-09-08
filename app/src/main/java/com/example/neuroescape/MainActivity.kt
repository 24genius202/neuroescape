package com.example.neuroescape

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraFrameProvider: CameraFrameProvider
    private lateinit var tfrunner: TfliteRunner
    private var exittimeout: Int = 0
    var debuginfo: DebugInfo = DebugInfo(0,0,0,0, 0, 0,0f,0,0f)


    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val AI_PROCESS_INTERVAL_MS = 100L
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
        // camera initialize
        cameraFrameProvider = CameraFrameProvider(this, binding.previewContainer as ViewGroup, this)
        cameraFrameProvider.startCamera(binding.cameraImage, binding.original, binding.crop)

        // set flash event listener
        binding.Flash.setOnCheckedChangeListener { _, isChecked ->
            {
                Log.d("DEBUGLOG", "flash $isChecked")
                cameraFrameProvider.enableflash(isChecked)
            }
        }



        Log.d("DEBUGLOG", "[MainActivity]Vibration coroutine start")
        //진동 안내 시작
        lifecycleScope.launch(Dispatchers.Default) {
            VibrationGuide.startvibratorguide(this@MainActivity, lifecycleScope)
        }

        Log.d("DEBUGLOG", "[MainActivity]tflite coroutine start")
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
                maincode()
                delay(AI_PROCESS_INTERVAL_MS)
            }
        }
    }

    private fun checkexit(result: List<Detection>): Boolean{
        for(i in result){
            if(i.classId == 3) return true
        }
        return false
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
            tfrunner.runcycle(frame)
        } catch (e: Exception) {
            Log.e("DEBUGLOG", "[MainActivity]TFLite run failed", e)
            return false
        }
        Log.d("DEBUGLOG", "[MainActivity]"+result.toString())



        //티임아웃 확인
        if(!checkexit(result)) exittimeout++
        else exittimeout = 0
        //진동안내 중단
        if(exittimeout == 2) VibratorTimer.activate = false

        for(i in result){
            Log.d("DEBUGLOG", "[MainActivity]" + " ㄴ " + i.toString())
            //언패킹 작업
            val classid: Int = i.classId
            val classconfidence: Float = i.confidence
            val box: Box = i.box

            val x = (box.x * cameraFrameProvider.getbitmapsize().first).toInt()
            val y = (box.y * cameraFrameProvider.getbitmapsize().second).toInt()
            val w = (box.width * cameraFrameProvider.getbitmapsize().first).toInt()
            val h = (box.height * cameraFrameProvider.getbitmapsize().second).toInt()

            Log.d("DEBUGLOG", "[MainActivity] x:$x y:$y w:$w h:$h")

            val referencepos: Float

            val context: Context = this

            when(classid){
                //진동 안내 실행

                //비상구
                3 -> {referencepos = abs(cameraFrameProvider.getbitmapsize().first/2 - x.toFloat()) / cameraFrameProvider.getbitmapsize().first
                    //.let { BigDecimal(it.toDouble()).setScale(6, RoundingMode.HALF_UP).toFloat() }
                    Log.d("DEBUGLOG", "[MainActivity] referencepos:$referencepos")
                    //referencepos 정상적으로 나옴
                    //진동안내 재개
                    VibratorTimer.activate = true
                    VibrationGuide.updatevibrator(referencepos, checkdistance(w, h))}
                // 손잡이
                0 -> { lifecycleScope.launch(Dispatchers.Default) {
                    if(!VoiceGuide.isrunning()) VoiceGuide.voiceguide(context, 2)
                }}
                1 -> { lifecycleScope.launch(Dispatchers.Default) {
                    if(!VoiceGuide.isrunning()) VoiceGuide.voiceguide(context, 3)
                }}
                2 -> { lifecycleScope.launch(Dispatchers.Default) {
                    if(!VoiceGuide.isrunning()) VoiceGuide.voiceguide(context, 4)
                }}
                // 난간
                5 -> { lifecycleScope.launch(Dispatchers.Default) {
                    if(!VoiceGuide.isrunning()) VoiceGuide.voiceguide(context, 1)
                }}
            }
        }

        return true
    }


    private fun checkdistance(w: Int, h: Int): Float{
        val distance = (w*h / cameraFrameProvider.getbitmapsize().first*cameraFrameProvider.getbitmapsize().second).toFloat()
        Log.d("DEBUGLOG", "[MainActivity] distance:$distance")
        return distance
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