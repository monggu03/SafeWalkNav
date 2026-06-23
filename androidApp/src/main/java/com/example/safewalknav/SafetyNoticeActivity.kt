package com.example.safewalknav

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 첫 실행 안전 고지 게이트.
 *
 * 흐름:
 *  - 이미 동의함(SharedPreferences) → 즉시 MainActivity 이동 (고지 스킵)
 *  - 미동의 → 고지 화면 표시
 *      · [동의]   → 동의 저장 후 MainActivity 이동
 *      · [비동의] → 차단 화면 ("다시 고지 보기" 로 복귀 가능)
 *
 * 접근성: TalkBack 사용 시 진입 문구를 announceForAccessibility 로 즉시 낭독.
 *        (스크린리더가 꺼져 있으면 낭독되지 않음 — 정상 동작.)
 */
class SafetyNoticeActivity : AppCompatActivity() {

    private lateinit var noticeContainer: LinearLayout
    private lateinit var blockedContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 이미 동의한 사용자는 고지 건너뛰고 바로 본 앱으로.
        if (hasAgreed()) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_safety_notice)

        noticeContainer = findViewById(R.id.noticeContainer)
        blockedContainer = findViewById(R.id.blockedContainer)

        findViewById<Button>(R.id.btnAgree).setOnClickListener {
            setAgreed()
            goToMain()
        }
        findViewById<Button>(R.id.btnDecline).setOnClickListener { showBlocked() }
        findViewById<Button>(R.id.btnReconsider).setOnClickListener { showNotice() }

        showNotice()
    }

    private fun showNotice() {
        noticeContainer.visibility = View.VISIBLE
        blockedContainer.visibility = View.GONE
        val t = findViewById<TextView>(R.id.tvNotice)
        t.post { t.announceForAccessibility(t.text) }
    }

    private fun showBlocked() {
        noticeContainer.visibility = View.GONE
        blockedContainer.visibility = View.VISIBLE
        val t = findViewById<TextView>(R.id.tvBlocked)
        t.post { t.announceForAccessibility(t.text) }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ---- SharedPreferences ----
    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun hasAgreed() = prefs().getBoolean(KEY_AGREED, false)
    private fun setAgreed() = prefs().edit().putBoolean(KEY_AGREED, true).apply()

    companion object {
        private const val PREFS = "safewalk_prefs"
        private const val KEY_AGREED = "hasAgreedToSafetyNotice"
    }
}
