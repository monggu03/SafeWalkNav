package com.example.safewalknav.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Android actual — AudioTrack 기반 사인파 합성 재생.
 *
 * 외부 wav 리소스 없이 동작하도록 메모리에서 사인파를 만들어 재생한다.
 * pan 은 좌우 채널 볼륨 차이로 구현 (`-1.0 → 오른쪽 무음`, `+1.0 → 왼쪽 무음`).
 *
 * 재생 후 AudioTrack 은 비동기로 풀에서 회수되도록 release 를 호출한다.
 * 짧은 비프 (≤ 150ms) 만 다루므로 풀링은 생략.
 */
actual class SpatialBeeper actual constructor() {

    private val sampleRate = 44_100

    private var lastTrack: AudioTrack? = null

    actual fun playBeep(pan: Float, tone: BeepTone, repeatCount: Int) {
        val freq = if (tone == BeepTone.HIGH) 1200 else 600
        val durationMs = if (tone == BeepTone.HIGH) 100 else 150
        val (leftGain, rightGain) = panToGain(pan)
        repeat(repeatCount.coerceAtLeast(1)) {
            playOnce(freq, durationMs, leftGain, rightGain)
        }
    }

    actual fun stop() {
        lastTrack?.runCatching { stop(); release() }
        lastTrack = null
    }

    private fun playOnce(freqHz: Int, durationMs: Int, leftGain: Float, rightGain: Float) {
        val totalSamples = sampleRate * durationMs / 1000
        // 스테레오 PCM16 — 좌/우 인터리빙
        val buffer = ShortArray(totalSamples * 2)
        val twoPiF = 2.0 * PI * freqHz
        // 짧은 attack/release 페이드로 클릭 노이즈 방지
        val fadeSamples = (sampleRate * 5 / 1000).coerceAtMost(totalSamples / 4)
        for (i in 0 until totalSamples) {
            val envelope = when {
                i < fadeSamples -> i.toFloat() / fadeSamples
                i > totalSamples - fadeSamples -> (totalSamples - i).toFloat() / fadeSamples
                else -> 1.0f
            }
            val sample = (sin(twoPiF * i / sampleRate) * envelope * Short.MAX_VALUE * 0.5).toInt().toShort()
            buffer[2 * i] = (sample * leftGain).toInt().toShort()
            buffer[2 * i + 1] = (sample * rightGain).toInt().toShort()
        }

        val bufSizeBytes = buffer.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufSizeBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(buffer, 0, buffer.size)
        track.setNotificationMarkerPosition(totalSamples)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) {
                runCatching { t.release() }
            }
            override fun onPeriodicNotification(t: AudioTrack) = Unit
        })
        track.play()
        lastTrack = track
    }

    private fun panToGain(pan: Float): Pair<Float, Float> {
        val clamped = pan.coerceIn(-1f, 1f)
        val left = if (clamped <= 0f) 1f else (1f - clamped)
        val right = if (clamped >= 0f) 1f else (1f + clamped)
        return left to right
    }
}
