@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.toGpsLocation
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLHeading

/**
 * iOS CLLocation을 TBFW의 UserState로 변환하는 확장 함수.
 *
 * 기존 CLLocationConverter의 toGpsLocation()을 재사용하므로
 * 변환 로직 중복이 없다.
 *
 * heading은 선택 인자. iOS에서 CLLocationManager.startUpdatingHeading()을
 * 호출하지 않으면 null로 넘기면 되고, UserState.effectiveHeading이
 * GPS bearing으로 fallback한다.
 *
 * @param heading CLHeading 객체 (선택). trueHeading을 사용 (자북 보정됨).
 * @return UserState — TrustBasedNavigator.update()에 바로 넘길 수 있음.
 *
 * 사용 예:
 *   // GPS만
 *   val state = currentLocation.toUserState()
 *
 *   // GPS + heading
 *   val state = currentLocation.toUserState(currentHeading)
 *
 *   // 그 후
 *   val result = navigator.update(state)
 */
fun CLLocation.toUserState(heading: CLHeading? = null): UserState = UserState(
    location = this.toGpsLocation(),
    heading = heading?.toEffectiveHeading()
)

/**
 * CLHeading에서 유효한 heading 값 추출.
 *
 * - trueHeading: 자기북이 아닌 진북 기준 (지도 좌표계와 일치)
 * - 음수면 무효값이므로 null 반환
 *
 * trueHeading이 음수일 때는 magneticHeading으로 fallback하지 않음.
 * trueHeading이 음수란 건 지자기 보정이 안 됐다는 뜻이고, 이때
 * magneticHeading을 쓰면 도심에서 오차가 커진다.
 */
private fun CLHeading.toEffectiveHeading(): Float? {
    val th = trueHeading
    return if (th >= 0) th.toFloat() else null
}
