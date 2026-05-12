package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.Waypoint

/**
 * 안내 문장 생성기.
 *
 * 우선순위 기반 메시지 빌더:
 *   1순위 — 종료/도착 메시지
 *   2순위 — 안전 경고 (Trust CRITICAL)
 *   3순위 — Trust LOW 보수적 안내
 *   4순위 — waypoint 통과 알림
 *   5순위 — 일반 거리 안내
 *
 * 보행 쏠림(좌우 보정) 안내는 현재 보류 — 팀 결정 후 추가.
 * 굽은 길 안내는 2차 구현으로 미룸.
 *
 * 모든 함수는 순수 함수 — 외부 상태 의존 없음.
 */
object MessageBuilder {

    /**
     * 메인 진입점 — 상황에 따라 적절한 메시지 생성.
     *
     * @param trustLevel 현재 신뢰도 카테고리
     * @param distance 현재 waypoint까지 거리 (m)
     * @param didPassWaypoint 이번 update에서 waypoint 통과 처리됐는지
     * @param isFinished 모든 waypoint 통과 완료 여부
     * @param currentTarget 현재 목표 waypoint (description 사용)
     * @return TTS로 읽을 문장
     */
    fun build(
        trustLevel: TrustLevel,
        distance: Float,
        didPassWaypoint: Boolean,
        isFinished: Boolean,
        currentTarget: Waypoint?
    ): String {
        // 1순위: 종료
        if (isFinished) return MSG_ARRIVED_DESTINATION

        // 2순위: Trust CRITICAL — 위치를 거의 못 믿는 상태
        if (trustLevel == TrustLevel.CRITICAL) {
            return MSG_TRUST_CRITICAL
        }

        // 3순위: Trust LOW — 거리 안내 대신 보수적 안내
        if (trustLevel == TrustLevel.LOW) {
            return MSG_TRUST_LOW
        }

        // 4순위: 방금 waypoint 통과
        if (didPassWaypoint) {
            return buildPassMessage(currentTarget)
        }

        // 5순위: 일반 거리 안내
        return buildDistanceMessage(distance, currentTarget)
    }

    // ─── 개별 메시지 빌더 ───

    /**
     * waypoint 통과 메시지.
     * waypoint description이 있으면 함께 안내.
     */
    internal fun buildPassMessage(target: Waypoint?): String {
        // 다음 목표 정보가 없으면 기본 메시지
        if (target == null) return MSG_PASSED_GENERIC

        // pointType에 따라 메시지 변형
        return when (target.pointType) {
            "CROSSWALK" -> "중간 지점을 통과했습니다. 다음 횡단보도로 안내합니다."
            "DESTINATION" -> "거의 도착했습니다."
            else -> MSG_PASSED_GENERIC
        }
    }

    /**
     * 거리 기반 일반 안내 메시지.
     * 거리에 따라 표현을 다르게 한다.
     */
    internal fun buildDistanceMessage(distance: Float, target: Waypoint?): String {
        val distanceInt = distance.toInt()

        // 매우 가까움 (3m 이내) — 곧 도착
        if (distance < 3f) {
            return when (target?.pointType) {
                "DESTINATION" -> MSG_ARRIVED_DESTINATION
                "CROSSWALK" -> "횡단보도 앞입니다. 신호를 확인해주세요."
                else -> "곧 다음 지점입니다."
            }
        }

        // 가까움 (10m 이내)
        if (distance < 10f) {
            return when (target?.pointType) {
                "CROSSWALK" -> "${distanceInt}미터 앞에 횡단보도가 있습니다."
                "DESTINATION" -> "목적지까지 ${distanceInt}미터 남았습니다."
                else -> "${distanceInt}미터 앞으로 이동하세요."
            }
        }

        // 일반 거리
        return "${distanceInt}미터 앞으로 이동하세요."
    }

    // ─── 메시지 상수 ───

    const val MSG_ARRIVED_DESTINATION = "목적지에 도착했습니다."
    const val MSG_PASSED_GENERIC = "중간 지점을 통과했습니다. 다음 지점으로 안내합니다."
    const val MSG_TRUST_CRITICAL = "위치 정확도가 매우 낮습니다. 잠시 멈춰주세요."
    const val MSG_TRUST_LOW = "위치 정확도가 낮습니다. 현재 방향을 유지하세요."
}
