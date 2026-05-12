package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.GpsLocation

/**
 * TBFW Navigator에 입력으로 들어가는 사용자 상태 묶음.
 *
 * GpsLocation은 위도/경도/속도/bearing/accuracy를 모두 가지고 있어서
 * UserState는 사실상 GpsLocation의 wrapper에 가깝다.
 *
 * 그런데 왜 별도 클래스로 만드는가?
 *  1. heading은 GPS bearing과 다를 수 있다.
 *     - GPS bearing: 사용자가 "이동하는" 방향 (멈춰 있으면 0)
 *     - heading: 사용자가 "바라보는" 방향 (멈춰 있어도 측정 가능)
 *     iOS의 CLLocationManager는 둘 다 제공하고, 보행자에게는 heading이 더 유용하다.
 *
 *  2. 카메라 결과 같은 추가 입력을 나중에 확장하기 쉽게 하기 위함.
 *     초기에는 GpsLocation + heading만 받지만, 나중에 cameraResult를 추가할 수 있다.
 *
 * @param location GpsLocation (위도/경도/accuracy 등)
 * @param heading 사용자가 바라보는 방향 (0~360°). null이면 GpsLocation.bearing 사용.
 */
data class UserState(
    val location: GpsLocation,
    val heading: Float? = null
) {
    /**
     * 실제 사용할 방향값.
     * heading이 있으면 heading을, 없으면 GPS bearing을 사용한다.
     */
    val effectiveHeading: Float
        get() = heading ?: location.bearing
}
