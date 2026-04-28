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
    val waypoints: List<Waypoint>, // 경유 포인트 리스트
    val routePoints: List<LatLng> = emptyList()  // 경로 전체 좌표 (지도 그리기용)
)

/** 위도/경도 쌍 */
data class LatLng(val lat: Double, val lon: Double)

/**
 * 경로 상의 핵심 안내 포인트
 * 교차로, 횡단보도, 방향전환 등 안내가 필요한 지점
 */
data class Waypoint(
    val lat: Double,               // 위도
    val lon: Double,               // 경도
    val turnType: Int,             // 회전 유형 (TMap 코드)
    val description: String,       // 안내 문구 ("우회전", "횡단보도 건넘" 등)
    val distance: Int,             // 다음 포인트까지 거리 (m)
    val roadType: Int,             // 도로 유형 (인도/차도 구분)
    val pointType: String          // "TURN", "CROSSWALK", "DESTINATION" 등
)

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
