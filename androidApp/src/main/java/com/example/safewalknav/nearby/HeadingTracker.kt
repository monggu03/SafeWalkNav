package com.example.safewalknav.nearby

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.safewalknav.navigation.geo.normalizeDeg
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 사용자가 향한 방향(진북 기준 방위각)을 추적한다. 시계 방향 안내의 "12시" 기준.
 *
 * ── 어느 방향을 "정면"으로 보나 ──
 * 휴대폰을 세로로 쥐고 화면이 나를 향한 자세라면, 기울기와 상관없이
 *   · 평평하게 들면  → 휴대폰 위쪽(+Y)이 앞을 가리키고
 *   · 세워 들면      → 후면 카메라(−Z)가 앞을 가리킨다.
 * 그 사이 어느 각도든 두 벡터의 **수평 성분은 같은 방향**이다. 그래서 (+Y) + (−Z) 를 수평면에
 * 투영한 방향을 정면으로 쓴다. 신호등 카메라를 들 때(가슴에 세워 듦)와 화면을 볼 때(눕혀 듦)
 * 모두 같은 "정면"이 나온다 — 자세를 바꿔도 시계 방향이 튀지 않는다.
 *
 * ── 신뢰도 ──
 * 자기장 센서 정확도가 UNRELIABLE 이거나, 회전 벡터가 보고한 방위 오차가 45° 를 넘으면
 * "믿을 수 없음"으로 본다(시계 한 칸 30° 보다 크게 틀릴 수 있다). 그때는 방향을 말하지 않고
 * 8자 보정을 안내한다. 사용자 요청대로 "엄청 나쁠 때"만 — LOW 정도는 그대로 쓴다.
 *
 * ── 자북 → 진북 ──
 * 회전 벡터는 자북 기준이다. 서울은 편각이 약 −9° 라 보정하지 않으면 시계로 1/3 칸 밀린다.
 * 위치를 알면 [setLocation] 으로 편각을 넣는다.
 */
class HeadingTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magneticSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)

    /** 지수 평활한 방위의 sin/cos (각도를 직접 평균하면 359°↔1° 경계에서 깨진다). */
    private var smoothSin = 0.0
    private var smoothCos = 0.0
    private var hasHeading = false

    /** 자기장 센서 정확도. 아직 보고 전이면 -1. */
    private var magneticAccuracy = -1

    /** 회전 벡터가 보고한 방위 오차(라디안). 지원 안 하면 -1. */
    private var headingAccuracyRad = -1f

    private var declinationDeg = 0f

    /** 이 기기에 방향을 낼 센서가 있는가. */
    val hasSensor: Boolean get() = rotationSensor != null

    /** 방향을 믿을 수 없는 상태인가 (8자 보정 필요). */
    val isUnreliable: Boolean
        get() = magneticAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            (headingAccuracyRad >= 0f && headingAccuracyRad > UNRELIABLE_HEADING_ACCURACY_RAD)

    fun start() {
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        // 자기장 센서는 값이 아니라 "정확도 보고"만 받으려고 등록한다.
        magneticSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        // 멈춰 있던 동안 사용자는 몸을 돌렸을 수 있다(신호등 카메라에 다녀오는 등). 옛 방향을 남겨 두면
        // 돌아온 직후 1초 남짓은 평활값이 따라오는 중이라 박스를 누르면 최대 180° 틀린 방향을 말한다.
        // 새 값이 올 때까지 "모름"으로 둔다.
        hasHeading = false
        headingAccuracyRad = -1f
    }

    /** 현재 위치로 편각(자북↔진북 차이)을 갱신. */
    fun setLocation(lat: Double, lon: Double) {
        declinationDeg = GeomagneticField(
            lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()
        ).declination
    }

    /** 진북 기준 정면 방위각(0~360). 아직 값이 없으면 null. */
    fun currentTrueHeadingDeg(): Double? {
        if (!hasHeading) return null
        val magnetic = Math.toDegrees(atan2(smoothSin, smoothCos))
        return normalizeDeg(magnetic + declinationDeg)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> magneticAccuracy = event.accuracy
            Sensor.TYPE_ROTATION_VECTOR -> onRotation(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magneticAccuracy = accuracy
    }

    private fun onRotation(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        // R(행 우선): world = R · device,  world 축 = (동, 북, 위)
        // 정면 벡터 device (0, +1, −1) 의 수평 성분
        val east = (rotationMatrix[1] - rotationMatrix[2]).toDouble()
        val north = (rotationMatrix[4] - rotationMatrix[5]).toDouble()
        if (hypot(east, north) < MIN_HORIZONTAL) return   // 거꾸로 든 경우 등 — 정면을 정할 수 없음

        if (event.values.size > 4) headingAccuracyRad = event.values[4]

        val azimuthRad = atan2(east, north)
        val s = sin(azimuthRad)
        val c = cos(azimuthRad)
        if (!hasHeading) {
            smoothSin = s
            smoothCos = c
            hasHeading = true
        } else {
            smoothSin += SMOOTHING * (s - smoothSin)
            smoothCos += SMOOTHING * (c - smoothCos)
        }
    }

    companion object {
        /** 지수 평활 계수. 작을수록 부드럽지만 몸을 돌린 뒤 따라오는 데 오래 걸린다. */
        private const val SMOOTHING = 0.2

        /** 45° — 시계 한 칸(30°)보다 확실히 크게 틀릴 수 있는 수준. */
        private const val UNRELIABLE_HEADING_ACCURACY_RAD = 0.785f

        private const val MIN_HORIZONTAL = 0.2
    }
}
