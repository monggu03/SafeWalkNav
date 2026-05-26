package com.example.safewalknav.onboarding

import android.content.Context
import android.util.Log
import com.example.safewalknav.navigation.geo.bearing
import com.example.safewalknav.navigation.tbfw.NavigatorConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 안내 시작 직후 자동 실행되는 온보딩 시퀀스 — iOS `AutoOnboardingCoordinator.swift` 의 Android 이식.
 *
 * 흐름:
 *   1. SUMMARY      — 경로 요약 발화 → [SUMMARY_DELAY_MS] 후 자동 진행
 *   2. FLAT_POSE    — 평평 자세 대기 ([PoseSensor]). [MAX_FLAT_POSE_MS] 초과 시 강제 진행.
 *                     디바이스에 TYPE_GRAVITY 없으면 즉시 건너뜀.
 *   3. ROTATING     — "천천히 도세요" + trueHeading 일치 진입 대기 ([HeadingSensor]).
 *                     [MAX_ROTATING_MS] 초과 시 [MAX_ROTATION_RETRIES] 회까지 재안내,
 *                     그래도 못 잡으면 강제 finish. 나침반 없으면 즉시 finish.
 *   4. CONFIRMING   — 일치 감지됨, trueHeading 이 [CONFIRM_HOLD_MS] 동안 허용오차 안에
 *                     머무는지 확인. 중간에 벗어나면 ROTATING 으로 복귀(멘트 재발화 X).
 *   5. DONE         — "정면입니다. 직진하세요" 발화 후 [onCompleted] 콜백 호출.
 *
 * 사용법:
 *   val coordinator = AutoOnboardingCoordinator(
 *       context = applicationContext,
 *       scope = lifecycleScope,
 *       speak = { msg -> speakTTS(msg) }
 *   )
 *   coordinator.start(
 *       summary = navigationManager.buildInitialSummary(),
 *       currentLat = location.latitude, currentLon = location.longitude,
 *       firstWaypointLat = wp.lat, firstWaypointLon = wp.lon,
 *       onCompleted = { startLocationTracking() }
 *   )
 *
 *   // 안내 종료/취소 시:
 *   coordinator.stop()
 *
 * @param context Application context (센서 등록용).
 * @param scope 단계 전환 타이머용 CoroutineScope (보통 Activity 의 lifecycleScope).
 * @param speak TTS 발화 콜백.
 * @param config NavigatorConfig — flatPoseGravity*Tolerance, initialHeadingToleranceDeg 사용.
 */
class AutoOnboardingCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val speak: (String) -> Unit,
    private val config: NavigatorConfig = NavigatorConfig.defaults(),
) {

    enum class Stage { IDLE, SUMMARY, FLAT_POSE, ROTATING, CONFIRMING, DONE }

    var stage: Stage = Stage.IDLE
        private set

    // 주의: 람다 본문은 별도 메서드 (onPoseUpdate / handleHeadingUpdate) 로 분리해야 한다.
    // 람다 안에서 poseSensor / headingSensor 자기 자신을 참조하면 초기화 사이클이 생겨
    // Kotlin 타입 추론이 "recursive problem" 으로 실패한다.
    private val poseSensor: PoseSensor = PoseSensor(context) { isFlat, _ ->
        onPoseUpdate(isFlat)
    }.apply {
        flatZTolerance = config.flatPoseGravityZTolerance
        flatXYTolerance = config.flatPoseGravityXYTolerance
    }

    private val headingSensor: HeadingSensor = HeadingSensor(context) { trueHeading ->
        handleHeadingUpdate(trueHeading)
    }

    private fun onPoseUpdate(isFlat: Boolean) {
        if (stage == Stage.FLAT_POSE && isFlat) {
            poseSensor.stop()
            startRotatingStage()
        }
    }

    private var targetBearing: Double = 0.0
    private var rotatingStartMs: Long = 0L
    private var flatPoseStartMs: Long = 0L
    private var confirmStartMs: Long = 0L
    private var rotationRetries: Int = 0
    private var onCompleted: (() -> Unit)? = null
    private var stageTimerJob: Job? = null

    /**
     * 온보딩 시작.
     *
     * @param summary 요약 멘트. 비어있으면 발화 건너뜀.
     * @param currentLat/Lon 현재 GPS — declination 보정 + 목표 bearing 계산 기준.
     * @param firstWaypointLat/Lon 첫 waypoint — 목표 bearing 의 끝점.
     * @param onCompleted 5단계 완료 또는 폴백으로 강제 종료 시 호출. 안내 본 시퀀스 시작 지점.
     */
    fun start(
        summary: String,
        currentLat: Double,
        currentLon: Double,
        firstWaypointLat: Double,
        firstWaypointLon: Double,
        onCompleted: () -> Unit,
    ) {
        // 이미 진행 중이면 정리하고 새로 시작
        stop()

        this.onCompleted = onCompleted
        this.rotationRetries = 0

        // 목표 bearing — 현재 위치에서 첫 waypoint 까지 (도)
        this.targetBearing = bearing(currentLat, currentLon, firstWaypointLat, firstWaypointLon)
            .toDouble()

        headingSensor.currentLat = currentLat
        headingSensor.currentLon = currentLon

        Log.d(TAG, "start — targetBearing=$targetBearing")

        // Stage 1: SUMMARY — 요약 발화 후 4초 대기
        stage = Stage.SUMMARY
        if (summary.isNotBlank()) speak(summary)

        stageTimerJob = scope.launch {
            delay(SUMMARY_DELAY_MS)
            if (stage == Stage.SUMMARY) startFlatPoseStage()
        }
    }

    /**
     * 진행 중인 온보딩 강제 종료. 센서/타이머 정리.
     * onCompleted 콜백은 호출되지 않는다 — 호출자가 별도로 정리해야 함.
     */
    fun stop() {
        stageTimerJob?.cancel()
        stageTimerJob = null
        poseSensor.stop()
        headingSensor.stop()
        if (stage != Stage.IDLE && stage != Stage.DONE) {
            stage = Stage.IDLE
        }
    }

    // ───────────────────────────── Stage 2: 평평 자세 대기 ─────────────────────────────

    private fun startFlatPoseStage() {
        stageTimerJob?.cancel()
        stage = Stage.FLAT_POSE
        flatPoseStartMs = System.currentTimeMillis()
        speak("스마트폰을 평평하게 들어주세요.")

        if (!poseSensor.isAvailable) {
            // TYPE_GRAVITY 없는 디바이스 — 자세 단계 생략
            Log.d(TAG, "TYPE_GRAVITY 없음 — 자세 단계 건너뜀")
            startRotatingStage()
            return
        }

        poseSensor.start()

        // 15초 폴백 — 못 잡으면 그대로 진행
        stageTimerJob = scope.launch {
            delay(MAX_FLAT_POSE_MS)
            if (stage == Stage.FLAT_POSE) {
                poseSensor.stop()
                speak("그대로 진행합니다.")
                startRotatingStage()
            }
        }
    }

    // ──────────────────────── Stage 3/4: 회전 + 일치 감지 ────────────────────────

    private fun startRotatingStage() {
        stageTimerJob?.cancel()
        stage = Stage.ROTATING
        rotatingStartMs = System.currentTimeMillis()
        speak("천천히 한 바퀴 도세요.")

        if (!headingSensor.isAvailable) {
            // TYPE_ROTATION_VECTOR 없음 — 정면 확인 불가
            Log.d(TAG, "TYPE_ROTATION_VECTOR 없음 — 정면 확인 생략하고 출발")
            speak("출발합니다.")
            finish()
            return
        }

        headingSensor.start()
    }

    private fun handleHeadingUpdate(trueHeading: Double) {
        if (trueHeading < 0) return  // 보정 실패 신호 — 무시

        var diff = targetBearing - trueHeading
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        val inTolerance = abs(diff) < config.initialHeadingToleranceDeg

        when (stage) {
            Stage.ROTATING -> {
                val elapsed = System.currentTimeMillis() - rotatingStartMs
                if (elapsed > MAX_ROTATING_MS) {
                    if (rotationRetries < MAX_ROTATION_RETRIES) {
                        rotationRetries++
                        rotatingStartMs = System.currentTimeMillis()
                        speak("다시 한번 천천히 도세요.")
                    } else {
                        speak("정면을 잡지 못했습니다. 그대로 출발합니다.")
                        finish()
                    }
                    return
                }

                if (inTolerance) {
                    stage = Stage.CONFIRMING
                    confirmStartMs = System.currentTimeMillis()
                    speak("방향이 맞습니다. 멈춰주세요.")
                }
            }

            Stage.CONFIRMING -> {
                if (!inTolerance) {
                    // 1초 유지 실패 — ROTATING 복귀 (멈춤 멘트 재발화 X)
                    stage = Stage.ROTATING
                    rotatingStartMs = System.currentTimeMillis()
                    return
                }

                val held = System.currentTimeMillis() - confirmStartMs
                if (held >= CONFIRM_HOLD_MS) {
                    speak("정면입니다. 직진하세요.")
                    finish()
                }
            }

            else -> { /* no-op — IDLE/SUMMARY/FLAT_POSE/DONE 단계에선 heading 무시 */ }
        }
    }

    // ───────────────────────────── 종료 ─────────────────────────────

    private fun finish() {
        val cb = onCompleted
        // 센서/타이머 정리 (stop() 이 stage 도 IDLE 로 되돌리므로 그 전에 콜백 보관)
        stop()
        stage = Stage.DONE
        cb?.invoke()
        onCompleted = null
    }

    companion object {
        private const val TAG = "Onboarding"

        // iOS AutoOnboardingCoordinator.swift 와 동일 상수 (단위만 ms 로 환산)
        private const val SUMMARY_DELAY_MS: Long = 4_000L
        private const val CONFIRM_HOLD_MS: Long = 1_000L
        private const val MAX_ROTATING_MS: Long = 15_000L
        private const val MAX_FLAT_POSE_MS: Long = 15_000L
        private const val MAX_ROTATION_RETRIES: Int = 1
    }
}
