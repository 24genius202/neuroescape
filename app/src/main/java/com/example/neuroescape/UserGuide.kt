package com.example.neuroescape

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.exp
import android.media.MediaPlayer
import android.util.Log

object VoiceGuide {

    fun voiceguide(context: Context, id: Int){
        val resId: Int
        val instresId: Int = R.raw.handleinstruction
        when(id){
            1 -> resId = R.raw.fence
            2 -> resId = R.raw.roundhandle
            3 -> resId = R.raw.leverhandle
            4 -> resId = R.raw.pushbarhandle
            else -> return
        }

        playMp3(context, resId)

        //손잡이 안내는 지시문도 실행
        if(id>1 && id<5){
            playMp3(context, instresId)
        }
    }

    private fun playMp3(context: Context, resId: Int) {
        val mediaPlayer = MediaPlayer.create(context, resId)
        mediaPlayer.start() // 재생 시작

        // 재생 완료 후 MediaPlayer 해제
        mediaPlayer.setOnCompletionListener {
            it.release()
        }
    }

}

object VibrationGuide {
    private var vib_amp: Float = 0.0f
    private var vib_freq: Float = 0.0f
    private var intervalMs: Long = 0L
    private var amplitude: Int = 0

    fun updatevibrator(x0: Float, x1: Float){
        var funx0 = (1.0 / (1.0 + exp(9.19 * (x0 - 0.5)))).toFloat()
        //if(funx0 < 0.05) funx0 = 0.0

        var funx1 = (1.0 / (1.0 + exp(9.19 * (x1 - 0.5)))).toFloat()
        //if(funx1 < 0.05) funx1 = 0.0

        vib_amp = funx1
        vib_freq = funx0

        Log.d("DEBUGLOG", "[UserGuide]updatevibrator | vib_freq: $vib_freq vib_amp: $vib_amp")

        intervalMs = 1000L - (vib_freq * 1000).toLong()
        // 1 ~ 255
        amplitude = 100 //((vib_amp * 255).toInt()).coerceIn(1, 255)

        Log.d("DEBUGLOG", "[UserGuide]updatevibrator | intervalMs: $intervalMs amplitude: $amplitude")

        LocalTimer.intervalMs = intervalMs
    }

    private fun vibrate(context: Context, durationMs: Long, amplitude: Int) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val vibrationEffect = VibrationEffect.createOneShot(durationMs.toLong(), amplitude)
        vibrator.vibrate(vibrationEffect)
    }

    fun startvibratorguide(context: Context, scope: CoroutineScope){
        // vib_freq 계산 → interval 최소 1ms
        intervalMs = (1000 - (vib_freq * 1000).toLong()).coerceAtLeast(1L)
        amplitude = 100

        // 안전하게 Coroutine 시작
        LocalTimer.start(intervalMs, scope) {
            // Activity 종료 시 crash 방지 위해 applicationContext 사용
            vibrate(context.applicationContext, intervalMs / 2, amplitude)
        }
    }


}


object LocalTimer {
    private var job: Job? = null

    @Volatile
    var intervalMs: Long = 1000L // 실행 중에도 바꿀 수 있음

    fun start(initialIntervalMs: Long, scope: CoroutineScope, task: () -> Unit) {
        stop()
        intervalMs = initialIntervalMs
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                task()
                delay(intervalMs.coerceAtLeast(1L))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}