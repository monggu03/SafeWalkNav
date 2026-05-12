package com.example.safewalknav.navigation.tbfw

/**
 * TBFW Navigator의 출력 묶음.
 *
 * 단순히 안내 문장 하나만 리턴하지 않고, 내부 판단 결과를 함께 노출한다.
 * 이렇게 하면 호출하는 쪽(ViewModel 등)이 추가 동작을 결정할 수 있다.
 * 예: trustScore가 낮으면 진동 패턴을 다르게, didPassWaypoint=true면 비프음 등.
 *
 * @param message TTS로 읽어줄 안내 문장
 * @param didPassWaypoint 이번 update에서 waypoint를 통과 처리했는지 여부
 * @param currentWaypointIndex 현재 목표 waypoint의 인덱스 (디버그/UI용)
 * @param trustScore 0~100, 현재 GPS 신뢰도 점수
 * @param trustLevel 점수를 카테고리화한 값 (HIGH/MEDIUM/LOW/CRITICAL)
 * @param distanceToWaypoint 현재 위치에서 다음 waypoint까지 거리 (m)
 * @param headingDiff 현재 heading - 목표 bearing (-180 ~ +180 도)
 * @param isFinished 모든 waypoint를 통과해서 더 이상 안내할 게 없는 상태
 */
data class NavigationResult(
    val message: String,
    val didPassWaypoint: Boolean,
    val currentWaypointIndex: Int,
    val trustScore: Int,
    val trustLevel: TrustLevel,
    val distanceToWaypoint: Float,
    val headingDiff: Float,
    val isFinished: Boolean,
)

/**
 * Trust Score를 카테고리로 표현.
 *
 * - HIGH (80+): GPS 매우 정확. 표준 안내.
 * - MEDIUM (60~79): GPS 보통. waypoint 통과 거리 완화.
 * - LOW (40~59): GPS 부정확. 거리 안내 대신 방향 유지 안내.
 * - CRITICAL (40미만): GPS 매우 부정확. waypoint 통과 보류, 사용자에게 잠시 멈추도록 안내.
 */
enum class TrustLevel {
    HIGH,
    MEDIUM,
    LOW,
    CRITICAL,
}
