package com.example.safewalknav.navigation

/**
 * POI(Point of Interest) 검색 결과.
 * TMap REST API의 /tmap/pois 응답을 파싱한 항목 하나에 해당.
 *
 * frontLat / frontLon — 건물 입구 좌표(있을 때만). 도착 판정 정확도를 높이기 위해
 * POI 본체 좌표와 함께 더 가까운 쪽을 사용.
 *
 * KMM commonMain — Android/iOS 공통.
 */
data class POIResult(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String,
    val frontLat: Double? = null,
    val frontLon: Double? = null
)
