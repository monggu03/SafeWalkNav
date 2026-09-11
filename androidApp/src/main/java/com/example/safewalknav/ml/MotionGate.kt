package com.example.safewalknav.ml

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 폰을 빠르게 휘두르는 동안 추론을 막는 게이트.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 왜 필요한가 (2026-09 현장 관찰)
 * ─────────────────────────────────────────────────────────────────────────────
 * 시각장애인 사용자는 신호등이 어디 있는지 모르기 때문에 폰을 좌우로 빠르게 휘젓는다.
 * 그런데 그 순간의 프레임은
 *   (1) 모션 블러로 뭉개져 모델이 못 잡고,
 *   (2) 어쩌다 잡혀도 [SignalDecisionEngine] 의 안정성 스트릭(빨강 4 / 초록 5 프레임)을
 *       계속 0 으로 되돌린다.
 * 즉 **휘두를수록 더 안 잡히는** 악순환이다. 사용자 입장에선 "아무리 해도 안 된다".
 *
 * 이 게이트는 각속도가 임계를 넘는 동안 추론 자체를 건너뛰게 하고
 * ([TrafficLightAnalyzer] 의 `isActive` 훅에 물린다), 그 상태가 일정 시간 이어지면
 * "천천히 돌려 주세요" 를 **한 번만** 말하도록 호출자에게 알린다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 센서 선택
 * ─────────────────────────────────────────────────────────────────────────────
 * 자이로([Sensor.TYPE_GYROSCOPE], rad/s)가 가장 정확하다. 회전만 재기 때문에
 * 걸으면서 생기는 상하 진동에 잘 안 속는다.
 * 자이로가 없는 저가 기기를 위해 가속도계 폴백을 둔다 — 중력 성분을 저역통과로
 * 추정해 빼고 남은 선형 가속도 크기를 쓴다. 덜 정확하므로 임계를 따로 둔다.
 * **둘 다 없으면 게이트는 그냥 꺼진다** (isFastMotion 이 영원히 false) — 앱은 정상 동작.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 스레드
 * ─────────────────────────────────────────────────────────────────────────────
 * [onSensorChanged] 는 메인 루퍼에서 불린다(핸들러 없이 등록). 따라서 두 콜백
 * ([onFastMotionStart] / [onSustainedFastMotion]) 안에서 TTS·진동·UI 를 바로 만져도 된다.
 * 반면 [isFastMotion] 은 카메라 분석 스레드가 읽으므로 @Volatile 이어야 한다.
 *
 * @param onFastMotionStart     빠른 움직임이 **시작된** 순간 1회. 조준 진동을 끄는 용도.
 * @param onSustainedFastMotion 빠른 움직임이 [SUSTAINED_MS] 이상 이어질 때.
 *                              [SUSTAINED_COOLDOWN_MS] 쿨다운이 걸려 말이 반복되지 않는다.
 */
class MotionGate(
    context: Context,
    private val onFastMotionStart: () -> Unit = {},
    private val onSustainedFastMotion: () -> Unit = {},
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** 자이로가 있으면 가속도계는 쓰지 않는다 (두 센서를 섞으면 임계가 뒤엉킨다). */
    private val accelerometer: Sensor? =
        if (gyroscope == null) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null

    private val sensor: Sensor? = gyroscope ?: accelerometer

    /** 쓸 센서가 하나라도 있는지. false 면 게이트는 아무것도 하지 않는다. */
    val isAvailable: Boolean get() = sensor != null

    /**
     * 지금 "빨리 휘두르는 중" 인가. 분석 스레드가 읽는다.
     * 센서가 없으면 항상 false — 게이트가 없는 것과 같다.
     */
    @Volatile
    var isFastMotion: Boolean = false
        private set

    private var smoothed = 0f
    private val gravity = floatArrayOf(0f, 0f, 0f)
    private var hasGravity = false

    /** 이번 "휘두르기 구간" 이 시작된 시각. 좌우 반전으로는 리셋되지 않는다 ([SLOW_LATCH_MS]). */
    private var fastSinceMs = 0L

    /** 느려진 시각. [SLOW_LATCH_MS] 이상 유지돼야 구간이 끝난 것으로 본다. */
    private var slowSinceMs = 0L
    private var sustainedFiredAt = 0L
    private var registered = false

    fun start() {
        val s = sensor ?: return
        if (registered) return
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        registered = true
    }

    fun stop() {
        if (registered) {
            sensorManager.unregisterListener(this)
            registered = false
        }
        reset()
    }

    /** 상태만 비운다 (등록은 유지). 카메라 재시작 시 이전 움직임을 끌고 가지 않도록. */
    fun reset() {
        isFastMotion = false
        smoothed = 0f
        hasGravity = false
        fastSinceMs = 0L
        slowSinceMs = 0L
        sustainedFiredAt = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        val isGyro = event.sensor.type == Sensor.TYPE_GYROSCOPE
        val raw = when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> magnitude(event.values)
            Sensor.TYPE_ACCELEROMETER -> linearAccelMagnitude(event.values)
            else -> return
        }

        // EMA 평활 — 센서 한 틱의 튐으로 게이트가 깜빡이지 않게.
        smoothed = smoothed * (1f - EMA_ALPHA) + raw * EMA_ALPHA

        val onThreshold = if (isGyro) GYRO_ON else ACC_ON
        val offThreshold = if (isGyro) GYRO_OFF else ACC_OFF
        val now = System.currentTimeMillis()

        // 히스테리시스 — 켜는 문턱과 끄는 문턱을 다르게 둬서 경계에서 떨지 않게.
        if (!isFastMotion && smoothed >= onThreshold) {
            isFastMotion = true
            slowSinceMs = 0L
            if (fastSinceMs == 0L) fastSinceMs = now   // 구간 시작 시각은 한 번만 찍는다
            onFastMotionStart()
        } else if (isFastMotion && smoothed <= offThreshold) {
            isFastMotion = false
            slowSinceMs = now
        }

        // ⚠️ 진폭 히스테리시스만으로는 부족하다.
        // 좌우로 휘젓는 동작은 **방향이 바뀔 때마다 각속도가 0 을 지난다.** 그때 곧바로
        // fastSinceMs 를 비우면, 한 번의 스윕(0.5~1초)이 끝날 때마다 타이머가 리셋돼
        // "1.5초 이상 계속 휘두름" 조건이 영영 성립하지 않는다. 정작 겨냥한 동작에서
        // 안내가 안 나가는 셈이다.
        // 그래서 "충분히 오래 느렸을 때"만 구간을 닫는 시간 래치를 둔다.
        if (!isFastMotion && slowSinceMs > 0L && now - slowSinceMs >= SLOW_LATCH_MS) {
            fastSinceMs = 0L
            slowSinceMs = 0L
        }

        // isFastMotion 조건을 함께 본다 — 래치(400ms) 때문에 "막 멈춘" 순간에도
        // fastSinceMs 가 잠깐 살아 있어서, 사용자가 이미 자세를 잡았는데
        // "천천히 돌려 주세요" 가 뒤늦게 나가는 일을 막는다.
        if (isFastMotion &&
            fastSinceMs > 0L &&
            now - fastSinceMs >= SUSTAINED_MS &&
            now - sustainedFiredAt >= SUSTAINED_COOLDOWN_MS
        ) {
            sustainedFiredAt = now
            onSustainedFastMotion()
        }
    }

    private fun magnitude(v: FloatArray): Float =
        sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    /**
     * 가속도계 폴백 — 저역통과로 중력을 추정해 빼고 남은 선형 가속도 크기.
     * 첫 샘플은 중력 추정의 시드로만 쓰고 0 을 돌려준다(그 순간엔 판단 근거가 없다).
     */
    private fun linearAccelMagnitude(values: FloatArray): Float {
        if (!hasGravity) {
            values.copyInto(gravity, 0, 0, 3)
            hasGravity = true
            return 0f
        }
        var sum = 0f
        for (i in 0..2) {
            gravity[i] = gravity[i] * (1f - GRAVITY_ALPHA) + values[i] * GRAVITY_ALPHA
            val d = values[i] - gravity[i]
            sum += d * d
        }
        return sqrt(sum)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* 쓰지 않음 */ }

    companion object {
        private const val EMA_ALPHA = 0.3f
        private const val GRAVITY_ALPHA = 0.08f

        // 자이로 각속도 (rad/s). 1.2 ≈ 초당 69° — "의도적으로 빠르게 휘두르는" 수준.
        // 조준하려고 천천히 돌리는 동작(≈ 0.3~0.6)은 통과시킨다.
        private const val GYRO_ON = 1.2f
        private const val GYRO_OFF = 0.7f

        // 가속도계 폴백 (m/s²). 자이로보다 걸음걸이 노이즈에 취약해 문턱을 높게.
        private const val ACC_ON = 3.5f
        private const val ACC_OFF = 2.0f

        /**
         * 이만큼 느린 상태가 이어져야 "휘두르기 구간" 이 끝난 것으로 본다.
         * 좌우 스윕의 방향 전환 구간(≈100 ms)보다 넉넉히 길고,
         * 실제로 멈춰 겨누기 시작한 것과는 구분될 만큼 짧게.
         */
        private const val SLOW_LATCH_MS = 400L

        /** 이만큼 계속 빠르면 "천천히 돌려 주세요". */
        private const val SUSTAINED_MS = 1_500L

        /** 같은 말을 반복하지 않기 위한 쿨다운. */
        private const val SUSTAINED_COOLDOWN_MS = 15_000L
    }
}
