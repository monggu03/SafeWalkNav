package com.example.safewalknav.a11y

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * 앱이 말하는 동안 다른 소리(음악·유튜브·다른 길안내 앱)를 **줄여 달라고** 시스템에 요청한다(오디오 포커스).
 *
 * 왜: 예전에는 포커스를 전혀 요청하지 않아서, 블루투스로 음악을 듣는 중이면 음악이 그대로 크게 나오고
 * 그 위에 "빨간불"이 섞여 묻혔다. 시각장애인에게 이 안내는 안전 정보다.
 *
 * GAIN_TRANSIENT_MAY_DUCK: 다른 앱을 멈추게 하지 않고 잠깐 작게만 한다. 말이 끝나면 [release] 로 돌려준다.
 * 요청이 거절돼도(통화 중 등) 말은 그대로 한다 — 안내를 못 하는 것보다 겹치는 게 낫다.
 */
class SpeechFocus(context: Context, attributes: AudioAttributes) {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val request: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()

    private var held = false

    fun acquire() {
        if (held) return
        val am = audioManager ?: return
        held = try {
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun release() {
        if (!held) return
        held = false
        try {
            audioManager?.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
    }
}
