package com.example.safewalknav

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.safewalknav.a11y.Speaker
import com.example.safewalknav.ble.BleScanActivity
import com.example.safewalknav.nearby.NearbyActivity

/**
 * 홈 — 기능 3개 (2026-10 축소안).
 *
 *  위    : 내 위치 · 주변 장소   → [NearbyActivity]
 *  가운데: 신호등 카메라          → [MainActivity] (기존 그대로)
 *  아래  : 음향신호기 켜기        → [BleScanActivity] (규격 확보 전까지는 블루투스 조사)
 *
 * 내비게이션(경로 안내)은 사용자 요청에 따라 넣지 않는다.
 *
 * 음성: TalkBack 사용자는 TalkBack 이 버튼을 읽는다. TalkBack 이 꺼진 사용자를 위해
 * 앱을 처음 열었을 때 한 번만 버튼 배치를 말해 준다(돌아올 때마다 말하면 잔소리).
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var speaker: Speaker
    private var skipNextResumeCue = false

    /** 첫 안내(버튼 배치)를 말하는 중인가 / 그게 화면을 떠나며 끊겼는가. */
    private var introPlaying = false
    private var introCutOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        title = "SafeWalk 홈"

        speaker = Speaker(this, statusView = null)

        findViewById<Button>(R.id.btnNearby).setOnClickListener { open(NearbyActivity::class.java) }
        findViewById<Button>(R.id.btnSignal).setOnClickListener { open(MainActivity::class.java) }
        findViewById<Button>(R.id.btnAps).setOnClickListener { open(BleScanActivity::class.java) }

        // (아이콘 바로가기는 SafetyNoticeActivity 가 홈+목적 화면을 한 번에 쌓는다 — 홈은
        //  사용자가 뒤로 가기를 했을 때 여기서 처음 만들어지고, 그때 아래 첫 안내가 나간다)

        // 프로세스당 한 번 (회전·재생성으로 다시 말하지 않게 savedInstanceState 도 본다)
        if (savedInstanceState == null && !introSpoken) {
            introSpoken = true
            speakIntro()
            skipNextResumeCue = true   // 바로 이어지는 onResume 에서 "홈입니다"를 겹쳐 말하지 않게
        }
    }

    /** 사용자가 고른 화면으로 — 버튼을 고른 것이니 하던 안내는 끊는다(다시 들려줄 필요도 없다). */
    private fun open(target: Class<*>) {
        introPlaying = false
        speaker.stop()
        startActivity(Intent(this, target))
    }

    private fun speakIntro() {
        introPlaying = true
        speaker.speak(INTRO)
    }

    /**
     * 다른 화면에서 돌아왔을 때 "홈입니다" 한 마디 (TalkBack 이 꺼진 사용자용).
     * TalkBack 사용자는 TalkBack 이 창 제목("SafeWalk 홈")을 읽으므로 Speaker 가 알아서 침묵한다.
     * 이게 없으면 TalkBack 없이 쓰는 사람은 뒤로 가기 후 어느 화면에 있는지 알 길이 없다.
     */
    override fun onResume() {
        super.onResume()
        if (skipNextResumeCue) {
            skipNextResumeCue = false
            return
        }
        // 첫 안내(버튼 배치)를 듣다가 홈 버튼으로 나갔다면, 돌아왔을 때 "홈입니다"가 아니라 배치를 다시 알려 준다.
        if (introCutOff) {
            introCutOff = false
            speakIntro()
            return
        }
        speaker.speak(BACK_HOME)
    }

    /** 홈 버튼·최근 앱으로 나가면 즉시 말을 멈춘다. */
    override fun onPause() {
        super.onPause()
        if (introPlaying && speaker.isBusy()) introCutOff = true
        introPlaying = false
        speaker.stop()
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        /** 아이콘 바로가기(res/xml/shortcuts.xml)가 넘기는 목적지. 값은 그 파일과 맞춰야 한다. */
        const val EXTRA_OPEN = "com.safewalknav.extra.OPEN"
        const val OPEN_SIGNAL = "signal"
        const val OPEN_NEARBY = "nearby"

        private var introSpoken = false

        private const val INTRO =
            "SafeWalk 입니다. 위에서부터 내 위치와 주변 장소, 신호등 카메라, 음향신호기 켜기 버튼이 있습니다."
        private const val BACK_HOME = "홈입니다."
    }
}
