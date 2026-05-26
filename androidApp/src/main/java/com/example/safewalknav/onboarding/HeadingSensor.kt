package com.example.safewalknav.onboarding

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 진북 (true north) 기준 방위각 센서 래퍼.
 *
 * 동작:
 *   1. `Sensor.TYPE_ROTATION_VECTOR` 로 디바이스 회전 받음
 *   2. `SensorManager.getRotationMatrixFromVector` → 3x3 회전 행렬
 *   3. `SensorManager.getOrientation` → [azimuth, pitch, roll] (rad, 자북 기준)
 *   4. 현재 GPS 좌표로 `GeomagneticField` 만들어 자북→진북 declination 보정
 *   5. trueHeading = (magneticAzimuth + declination + 360) % 360
 *
 * GPS 위치 (currentLat/Lon/Altitude) 가 갱신될 때마다 declination 이 정확해진다.
 * 미설정 시 declination=0 (자북 ≒ 진북 가정) — 한국에서는 약 -8°~-10° 오차.
 *
 * @param context Application context (SensorManager 획득용).
 * @param onHeading (trueHeadingDeg: 0.0~360.0) 콜백 — 메인 스레드에서 호출.
 */
class HeadingSensor(
    private val context: Context,
    private val onHeading: (trueHeadingDeg: Double) -> Unit,
) : SensorEventListener {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** TYPE_ROTATION_VECTOR 지원 여부. 미지원 시 코디네이터가 회전 단계 건너뜀. */
    val isAvailable: Boolean get() = rotationSensor != null

    /** 현재 GPS 위도 — declination 보정용. 외부에서 GPS 업데이트 시 갱신. */
    var currentLat: Double = 0.0

    /** 현재 GPS 경도 — declination 보정용. */
    var currentLon: Double = 0.0

    /** 현재 해발 고도 (m). 모르면 0f. 고도가 declination 에 미치는 영향은 매우 작음. */
    var currentAltitude: Float = 0f

    private var running: Boolean = false
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start() {
        if (running) return
        rotationSensor?.let {
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
        if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        // orientation[0] = azimuth(rad), magnetic north 기준, -PI ~ +PI
        val magneticAzimuthDeg =
            (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0

        val declination = if (currentLat != 0.0 || currentLon != 0.0) {
            GeomagneticField(
                currentLat.toFloat(),
                currentLon.toFloat(),
                currentAltitude,
                System.currentTimeMillis(),
            ).declination.toDouble()
        } else {
            0.0  // GPS 미설정 — 자북 ≒ 진북 가정 (한국 ~-8°)
        }

        val trueHeading = (magneticAzimuthDeg + declination + 360.0) % 360.0
        onHeading(trueHeading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
}
