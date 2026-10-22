package com.example.safewalknav.a11y

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * 일반 화면(홈·안전 고지·주변 장소·음향신호기)의 **볼륨 아래 키 = 다시 듣기.**
 *
 * 왜: 신호등 카메라에서 "볼륨 아래 짧게 = 다시 듣기"를 배운 사람이 다른 화면에서 같은 키를 누르면,
 * 예전에는 실제로 볼륨이 내려갔다. 안내를 놓쳐서 누른 건데 소리가 더 작아지고, 몇 번 누르면 무음이 된다.
 * TalkBack 없이 쓰는 사람에게는 화면을 보지 않고 다시 들을 유일한 방법이기도 하다.
 *
 *  - 짧게 누름 → [onRepeat] (그 화면의 마지막 안내를 다시)
 *  - 길게 누르고 있음 → 원래대로 볼륨을 내린다. 단 **1칸 밑으로는 내리지 않는다** — 앱 안에서 실수로
 *    완전히 꺼 버리면 그 사실조차 소리로 알릴 수 없다.
 *  - TalkBack 켜짐 → 손대지 않는다(TalkBack 사용자는 상태 줄로 다시 듣고, 볼륨 키는 TalkBack 볼륨용).
 *
 * 볼륨 **올리기** 키는 건드리지 않는다 — 소리를 키울 길은 언제나 열어 둔다.
 */
class VolumeDownRepeat(
    private val activity: Activity,
    private val onRepeat: () -> Unit,
) {
    private var held = false

    /** Activity.onKeyDown 에서 먼저 부른다. true 면 처리했으니 그대로 true 를 돌려준다. */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!handles(keyCode)) return false
        if (event.repeatCount == 0) {
            held = false
            return true
        }
        held = true
        if (event.repeatCount % HOLD_REPEATS_PER_STEP == 0) lowerVolumeKeepingAudible()
        return true
    }

    /** Activity.onKeyUp 에서 먼저 부른다. */
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!handles(keyCode)) return false
        // 취소된 누름(예: 누르는 도중 전화가 옴)은 다시 듣기로 치지 않는다.
        if (!held && !event.isCanceled) onRepeat()
        held = false
        return true
    }

    private fun handles(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && !Speaker.isScreenReaderOn(activity)

    private fun lowerVolumeKeepingAudible() {
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val stream = AudioRouting.speechStream(activity)
        try {
            if (am.getStreamVolume(stream) <= 1) return
            am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        } catch (_: Exception) {
        }
    }

    private companion object {
        /** 키를 누르고 있으면 반복 이벤트가 약 50ms 마다 온다 — 4번에 한 칸(약 0.2초). */
        const val HOLD_REPEATS_PER_STEP = 4
    }
}
