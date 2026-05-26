package com.example.safewalknav.onboarding

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * TYPE_GRAVITY 기반 평평 자세 (flat pose) 판정 센서 래퍼.
 *
 * iOS 의 `CMDeviceMotion.gravity` 가 (x, y, z) 단위벡터(±1.0)로 들어오는 반면
 * Android `TYPE_GRAVITY` 는 m/s² 단위 + 부호 컨벤션이 반대다.
 *
 * 변환 규칙:
 *   - Android: 폰을 평평하게 들었을 때 (화면 위로) gravity ≈ (0, 0, +9.81)
 *   - iOS:    같은 자세에서 gravity ≈ (0, 0, -1.0)
 *   - 본 클래스는 `SensorManager.GRAVITY_EARTH` (9.80665) 로 나눠 단위벡터로 정규화한
 *     뒤, **Android 기준 부호** 로 비교한다 (gz ≈ +1.0 이 평평).
 *
 * NavigatorConfig 의 `flatPoseGravityZTolerance` (=0.2) / `flatPoseGravityXYTolerance` (=0.3)
 * 는 iOS 단위벡터 기준 임계값이라 정규화된 값과 그대로 비교 가능.
 *
 * @param context Application context (SensorManager 획득용).
 * @param onUpdate (isFlat, gravityNormalized[gx, gy, gz]) 콜백 — 메인 스레드에서 호출됨.
 */
class PoseSensor(
    private val context: Context,
    private val onUpdate: (isFlat: Boolean, gravityNormalized: FloatArray) -> Unit,
) : SensorEventListener {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    /** 디바이스에 TYPE_GRAVITY 가 있는지. 없으면 AutoOnboardingCoordinator 가 자세 단계 건너뜀. */
    val isAvailable: Boolean get() = gravitySensor != null

    /** Z 축 평평 임계 (단위벡터 기준, iOS 와 동일). 기본 0.2 — 즉 gz 가 1.0 ± 0.2 안에 들어와야 평평. */
    var flatZTolerance: Double = 0.2

    /** X, Y 축 평평 임계 (단위벡터 기준). 기본 0.3 — 폰이 좌우/앞뒤로 기울어졌는지 판정. */
    var flatXYTolerance: Double = 0.3

    private var running: Boolean = false

    fun start() {
        if (running) return
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            running = true
        }
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_GRAVITY) return
        // m/s² → 단위벡터 (-1.0 ~ +1.0)
        val gx = event.values[0] / SensorManager.GRAVITY_EARTH
        val gy = event.values[1] / SensorManager.GRAVITY_EARTH
        val gz = event.values[2] / SensorManager.GRAVITY_EARTH

        // Android: 평평 자세에서 gz ≈ +1.0 (iOS 는 -1.0). 부호 반대 주의.
        val zOk = abs(gz - 1.0f) < flatZTolerance
        val xyOk = abs(gx) < flatXYTolerance && abs(gy) < flatXYTolerance
        val isFlat = zOk && xyOk

        onUpdate(isFlat, floatArrayOf(gx, gy, gz))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
}
