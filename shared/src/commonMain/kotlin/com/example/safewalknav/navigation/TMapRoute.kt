package com.example.safewalknav.navigation

/**
 * TMap 보행자 경로 데이터 모델
 * TMap REST API 응답을 파싱해서 이 클래스에 담는다.
 *
 * KMM commonMain — Android/iOS 공통.
 */
data class TMapRoute(
    val totalDistance: Int,        // 전체 거리 (m)
    val totalTime: Int,            // 전체 소요시간 (초)
    val waypoints: List<Waypoint>, // 경유 포인트 리스트 (Point feature)
    val routePoints: List<LatLng> = emptyList(),  // 경로 전체 좌표 (지도 그리기용)
    val segments: List<RouteSegment> = emptyList() // 구간 단위 정보 (LineString feature)
) {
    /**
     * 주어진 waypoint 인덱스에서 시작하는 segment 반환.
     * (currentWaypointIndex 통과 후 진입하게 될 segment.)
     */
    fun segmentEnteringFromWaypoint(waypointIndex: Int): RouteSegment? =
        segments.firstOrNull { it.fromWaypointIndex == waypointIndex }
}

/** 위도/경도 쌍 */
data class LatLng(val lat: Double, val lon: Double)

/**
 * 경로 상의 핵심 안내 포인트 (TMap Point feature 1개).
 * 교차로, 횡단보도, 방향전환 등 안내가 필요한 지점.
 *
 * NOTE: roadType 필드는 backward-compat 용으로 남겨두지만
 *       Point feature 에는 실제 roadType 이 없어 항상 0 으로 채워진다.
 *       구간의 도로 유형이 필요하면 [TMapRoute.segments] 의 [RouteSegment.roadType] 을 사용할 것.
 */
data class Waypoint(
    val lat: Double,               // 위도
    val lon: Double,               // 경도
    val turnType: Int,             // 회전 유형 (TMap 코드)
    val description: String,       // 안내 문구 ("우회전", "횡단보도 건넘" 등)
    val distance: Int,             // 다음 포인트까지 누적 거리 (m, properties.totalDistance)
    val roadType: Int,             // ⚠️ Point 엔 roadType 없음 — 항상 0. RouteSegment.roadType 사용.
    val pointType: String          // "TURN", "CROSSWALK", "DESTINATION" 등
)

/**
 * 두 Waypoint 사이의 경로 구간 (TMap LineString feature 1개).
 * 도로 속성 + 위험도 계산 결과를 담는다.
 */
data class RouteSegment(
    val fromWaypointIndex: Int,    // 시작 waypoint 인덱스 (이 segment 진입 직전 통과한 Point)
    val toWaypointIndex: Int,      // 끝 waypoint 인덱스 (다음에 도달할 Point)
    val distance: Int,             // 구간 거리 (m, LineString properties.distance)
    val time: Int,                 // 구간 소요시간 (초, properties.time. 없으면 0)
    val roadType: Int,             // LineString properties.roadType (TMap 도로유형 코드)
    val facilityType: Int,         // LineString properties.facilityType. 없으면 -1
    val name: String,              // 도로명 ("테헤란로", "보행자도로" 등)
    val points: List<LatLng>,      // 구간 폴리라인 좌표
    val riskLevel: RiskLevel       // RiskScoreCalculator 산출값
)

/**
 * 구간 위험도 등급.
 * 비용 함수 결과를 사용자 안내 정책에 매핑하기 위한 enum.
 *
 *   SAFE    — 보행자도로 단독 구간. 사전 안내 생략.
 *   NORMAL  — 일반 인도, 차도 옆 인도. 표준 안내.
 *   CAUTION — 횡단보도 직후/주변, 자전거도로. 안내 강화.
 *   DANGER  — 계단, 육교, 지하도. 50m 전 사전 안내.
 */
enum class RiskLevel { SAFE, NORMAL, CAUTION, DANGER }

/**
 * 목적지 도착 상태
 * 단계별로 점점 상세한 안내를 제공
 */
enum class ArrivalState {
    FAR,            // 15m 이상: 일반 경로 안내
    APPROACHING,    // 15m 이내: "목적지 근처입니다"
    NEAR,           // 5m 이내: 방향 + 거리 상세 안내
    ARRIVED         // 3m 이내: "목적지 도착"
}
