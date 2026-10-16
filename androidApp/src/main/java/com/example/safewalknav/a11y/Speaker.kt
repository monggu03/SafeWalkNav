package com.example.safewalknav.a11y

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import java.util.Locale

/**
 * 홈·주변 장소·음향신호기 화면의 음성 안내 창구.
 *
 * ── TalkBack 과 겹치지 않게 ──
 * 이 화면들은 버튼이 있는 일반 화면이라 TalkBack 사용자는 TalkBack 으로 조작한다.
 * 그 위에 앱이 TTS 로 또 말하면 두 목소리가 겹친다(신호등 카메라 화면과 다른 점 —
 * 거기는 누를 것이 없어 TalkBack 을 통째로 침묵시켰다).
 *
 *  - TalkBack 켜짐 → TTS 를 쓰지 않고, 상태 글씨([statusView], accessibilityLiveRegion=polite)를
 *    바꾼다. TalkBack 이 자기 목소리·속도로 읽는다.
 *  - TalkBack 꺼짐 → TTS 로 말한다(접근성 용도 오디오).
 *    ⚠️ 접근성 볼륨이 따로 노는 건 TalkBack 같은 접근성 서비스가 켜져 있을 때뿐이다. 꺼져 있으면
 *       미디어 볼륨을 따라가므로, 미디어를 0 으로 둔 사람에게는 안 들린다. 그래서 첫 발화 때 볼륨이
 *       0 이면 진동(길게 세 번)으로 알린다 — 소리로는 알릴 방법이 없다.
 *
 * 상태 글씨는 두 경우 모두 바꾼다 — 저시력자·보호자가 눈으로도 본다.
 */
class Speaker(
    context: Context,
    private val statusView: TextView?,
) : TextToSpeech.OnInitListener {

    // ⚠️ 상태 필드는 반드시 tts 보다 **위에** 선언한다.
    //    TextToSpeech 생성자는 엔진 바인딩 실패 시 onInit(ERROR) 를 생성자 안에서 동기로 부른다.
    //    필드가 아래에 있으면 onInit 이 세운 unavailable=true 를 뒤이은 초기화(=false)가 덮어쓴다.
    private val appContext = context.applicationContext
    private var ready = false
    private var unavailable = false

    /** 엔진 준비 전에 들어온 말. 준비되면 마지막 것만 말한다(오래된 안내를 몰아서 읽지 않게). */
    private var pending: String? = null

    /** 같은 문장을 다시 넣어도 라이브 리전이 읽도록 번갈아 붙이는 보이지 않는 문자. */
    private var toggle = false

    /** 창이 포커스를 얻기 전에 들어와 TalkBack 이 못 읽었을 문장 — 포커스를 얻으면 다시 건다. */
    private var unannounced: String? = null
    private var waitingForWindowFocus = false

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val tts: TextToSpeech = TextToSpeech(appContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            unavailable = true
            return
        }
        val lang = tts.setLanguage(Locale.KOREAN)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            unavailable = true
            return
        }
        tts.setAudioAttributes(attributes)
        ready = true
        pending?.let { speakTts(it, interrupt = true) }
        pending = null
    }

    /** TalkBack(터치 탐색)이 켜져 있는가. */
    fun isScreenReaderOn(): Boolean = isScreenReaderOn(appContext)

    companion object {
        /** TalkBack(터치 탐색)이 켜져 있는가. 다른 화면(신호등 카메라 등)도 같은 기준을 쓴다. */
        fun isScreenReaderOn(context: Context): Boolean {
            val am = context.applicationContext
                .getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            return am?.isEnabled == true && am.isTouchExplorationEnabled
        }
    }

    /**
     * @param interrupt true 면 말하던 것을 끊고 바로 말한다(사용자가 방금 누른 것에 대한 응답).
     */
    fun speak(text: String, interrupt: Boolean = true) {
        statusView?.let { setLiveText(it, text) }
        if (isScreenReaderOn()) return   // TalkBack 이 라이브 리전으로 읽는다
        warnIfMuted()
        if (unavailable) return
        if (!ready) {
            pending = text
            return
        }
        speakTts(text, interrupt)
    }

    /**
     * 말하던 것과 **엔진 준비를 기다리던 말**을 모두 버린다.
     * 예전에는 기다리던 말(pending)이 남아, 콜드 스타트에 홈 안내가 준비되기 전에 버튼을 누르면
     * 그 홈 안내가 다음 화면(주변 장소·신호등 카메라) 위에서 뒤늦게 나왔다.
     */
    fun stop() {
        pending = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        pending = null
        tts.stop()
        tts.shutdown()
    }

    private var mutedWarned = false

    /** 볼륨이 0 이면 한 번 진동으로 알린다 (신호등 카메라 화면과 같은 패턴: 길게 세 번). */
    private fun warnIfMuted() {
        if (mutedWarned) return
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val level = try {
            am.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY)
        } catch (_: Exception) {
            return
        }
        if (level > 0) return
        mutedWarned = true
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        try {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1),
                attributes,
            )
        } catch (_: Exception) {
        }
    }

    /**
     * 라이브 리전 글씨를 바꾼다. 글씨는 언제나 즉시 바꾸되(눈으로 보는 사람용),
     * 창이 아직 포커스를 못 얻었으면 TalkBack 이 그 변화를 놓치므로 포커스를 얻는 순간 한 번 더 건드린다.
     * (화면 진입 직후 "확인하고 있습니다", 권한 대화상자 직후 "권한이 필요합니다" 가 여기 해당)
     */
    private fun setLiveText(v: TextView, text: String) {
        toggle = !toggle
        v.text = if (toggle) text else "$text​"
        if (v.isAttachedToWindow && v.hasWindowFocus()) {
            unannounced = null
            return
        }
        unannounced = text
        if (waitingForWindowFocus) return
        waitingForWindowFocus = true
        WindowFocus.runWhenFocused(v) {
            waitingForWindowFocus = false
            val t = unannounced ?: return@runWhenFocused
            unannounced = null
            toggle = !toggle
            v.text = if (toggle) t else "$t​"
        }
    }

    private fun speakTts(text: String, interrupt: Boolean) {
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "safewalk-${System.nanoTime()}")
    }
}
