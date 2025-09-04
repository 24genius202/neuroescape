package com.example.neuroescape

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
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
    private var exittimeout: Int = 0
    private var debuginfo: DebugInfo = DebugInfo(0,0,0,0,0f,0,0f)


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
        // camera initialize
        cameraFrameProvider = CameraFrameProvider(this, binding.previewContainer as ViewGroup, this)
        cameraFrameProvider.startCamera(findViewById<ImageView>(R.id.camera_image), findViewById<Button>(R.id.original), findViewById<Button>(R.id.crop))

        val flash = findViewById<Switch>(R.id.Flash)
        binding.Flash.setOnCheckedChangeListener { _, isChecked ->
            cameraFrameProvider.enableflash(isChecked)
        }

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

            val originwidthveiw = findViewById<TextView>(R.id.originalwidth)
            val originalheightveiw = findViewById<TextView>(R.id.originalheight)
            val croppedwidthveiw = findViewById<TextView>(R.id.croppedwidth)
            val croppedheightview = findViewById<TextView>(R.id.croppedheight)

            while (isActive) { // 코루틴이 살아있는 동안 반복
                val resultDetected = maincode() // AI 처리 (백그라운드)



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
            tfrunner.runcycle(frame, this)
        } catch (e: Exception) {
            Log.e("DEBUGLOG", "[MainActivity]TFLite run failed", e)
            return false
        }
        Log.d("DEBUGLOG", "[MainActivity]"+result.toString())


        val postprocessedresult = finalpostprocess(result)


        //티임아웃 확인
        if(!checkexit(result)) exittimeout++
        else exittimeout = 0
        //진동안내 중단
        if(exittimeout == 2) LocalTimer.activate = false

        for(i in postprocessedresult){
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

            when(classid){
                //진동 안내 실행

                //비상구
                3 -> {referencepos = abs(320 - x.toFloat()) / 640
                    //.let { BigDecimal(it.toDouble()).setScale(6, RoundingMode.HALF_UP).toFloat() }
                    Log.d("DEBUGLOG", "[MainActivity] referencepos:$referencepos")
                    //referencepos 정상적으로 나옴
                    //진동안내 재개
                    LocalTimer.activate = true
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

    private fun finalpostprocess(result: List<Detection>): List<Detection>{
        val newdetections = mutableListOf<Detection>()

        val originalbitmapwidth = cameraFrameProvider.getbitmapsize().first
        val croppedbitmapwidth = tfrunner.getinputsize().beforewidth
        val croppedbitmapx = tfrunner.getinputsize().beforeheight

        val cropscale = tfrunner.getinputsize().scale

        val croppedbitmapx1 = (croppedbitmapx-croppedbitmapwidth/2).toInt()

        Log.d("DEBUGLOG", "[MainActivity]originalbitmapwidth:$originalbitmapwidth croppedbitmapwidth:$croppedbitmapwidth croppedbitmapx:$croppedbitmapx")

        //crop 기준 픽셀 x 구하기 -> 기존으로 재스케일링 -> 원본 사진에서 픽셀 x 구하기 -> 그걸 상대 위치로 변환

        for(i in result){
            val absboxx = (i.box.x * croppedbitmapwidth).toInt()
            //절대 x 구하기
            var absolutex: Float

            var bboxabsx = absboxx + croppedbitmapx1

            //리스케일링
            bboxabsx /= cropscale.toInt()

            //절대 위치들을 상대 위치로 변경
            absolutex = bboxabsx / originalbitmapwidth.toFloat()

            Log.d("DEBUGLOG", "[MainActivity]bboxabsx:$bboxabsx absolutex:$absolutex")

            val newbox = Box(absolutex, i.box.y, i.box.width, i.box.height)

            newdetections.add(Detection(i.classId, i.confidence, newbox))

            debuginfo = DebugInfo(originalbitmapwidth, 0, croppedbitmapwidth, 0, 0f, bboxabsx, absolutex)
        }
        return newdetections
    }

    private fun checkdistance(w: Int, h: Int): Float{
        return (w*h / 640*640).toFloat()
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