package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.geo.distanceBetween
import com.example.safewalknav.navigation.platform.GpsLocation

/**
 * GPS 점프 감지 결과.
 *
 * 도보 가정 하에서 직전 위치와 현재 위치 간 함의 속도(implied speed)로 판정한다.
 *
 * NORMAL  — 보행 범위 내. 정상 통과 판정 진행.
 * SUSPECT — 의심스러운 속도(>5 m/s). 1회는 통과 판정만 보류, 안내는 정상.
 * JUMPED  — 명백한 튐(>10 m/s 또는 2회 연속 SUSPECT). 통과 보류 + 안내 정책 적용.
 */
enum class GpsJumpLevel {
    NORMAL,
    SUSPECT,
    JUMPED,
}

/**
 * GPS 점프 이상치 탐지기.
 *
 * 매 GPS 업데이트마다 update()를 호출해 직전 위치와 비교한다.
 * 첫 호출은 비교 대상이 없으므로 항상 NORMAL을 반환한다.
 *
 * @param config 임계값 설정. 기본값 권장.
 */
class GpsJumpDetector(
    private val config: NavigatorConfig = NavigatorConfig(),
) {
    private var lastLocation: GpsLocation? = null
    private var lastTimestampMs: Long = 0
    private var consecutiveSuspectCount: Int = 0

    /**
     * GPS 업데이트 처리.
     *
     * @param current 현재 GPS 위치
     * @param currentTimeMs 현재 시각 (ms, epoch). 호출자가 currentTimeMillis()로 넘김.
     * @return 점프 판정 결과
     */
    fun update(current: GpsLocation, currentTimeMs: Long): GpsJumpLevel {
        val prev = lastLocation
        val prevTime = lastTimestampMs

        // 다음 호출 비교를 위해 현재 값을 기록(판정 전에 갱신해도 무방)
        lastLocation = current
        lastTimestampMs = currentTimeMs

        // 첫 호출 — 비교 대상 없음
        if (prev == null || prevTime == 0L) {
            return GpsJumpLevel.NORMAL
        }

        val dtSec = (currentTimeMs - prevTime) / 1000.0
        if (dtSec <= 0.0) {
            // 시계가 거꾸로 가는 비정상 케이스 — 안전하게 NORMAL
            return GpsJumpLevel.NORMAL
        }

        val distance = distanceBetween(
            prev.latitude, prev.longitude,
            current.latitude, current.longitude
        )
        val impliedSpeed = distance / dtSec

        return classify(impliedSpeed)
    }

    /**
     * 함의 속도 → 점프 레벨 분류 + 연속 카운트 관리.
     *
     * 임계값 (모두 NavigatorConfig에서):
     *  - jumpSpeedCritical (기본 10.0 m/s): 단발성이라도 JUMPED
     *  - jumpSpeedSuspect (기본 5.0 m/s): SUSPECT. 2회 연속이면 JUMPED 승격.
     *  - 그 외: NORMAL, 연속 카운트 리셋.
     */
    private fun classify(impliedSpeed: Double): GpsJumpLevel {
        return when {
            impliedSpeed >= config.jumpSpeedCritical -> {
                consecutiveSuspectCount = 0  // critical은 연속 카운트와 무관
                GpsJumpLevel.JUMPED
            }
            impliedSpeed >= config.jumpSpeedSuspect -> {
                consecutiveSuspectCount += 1
                if (consecutiveSuspectCount >= config.suspectStreakForJump) {
                    GpsJumpLevel.JUMPED
                } else {
                    GpsJumpLevel.SUSPECT
                }
            }
            else -> {
                consecutiveSuspectCount = 0
                GpsJumpLevel.NORMAL
            }
        }
    }

    /** 디버그용 — 현재 누적 카운트 노출. */
    fun debugConsecutiveSuspect(): Int = consecutiveSuspectCount
}
