// 📁 shared/commonMain/.../navigation/SegmentAnalyzer.kt

package com.example.safewalknav.navigation

enum class DangerLevel { SAFE, CAUTION, DANGER }

data class SegmentRisk(
    val fromIndex: Int,           // 시작 waypoint 인덱스
    val toIndex: Int,             // 끝 waypoint 인덱스
    val score: Double,            // 위험도 점수 (0 = 안전, 높을수록 위험)
    val dangerLevel: DangerLevel, // SAFE / CAUTION / DANGER
    val reasons: List<String>,    // "차도 통과", "횡단보도", "계단" 등
    val distance: Int             // 구간 거리 (m)
)

object SegmentAnalyzer {

    fun analyze(route: TMapRoute): List<SegmentRisk> {
        val results = mutableListOf<SegmentRisk>()

        for (i in 0 until route.waypoints.size - 1) {
            val wp = route.waypoints[i]
            var score = 0.0
            val reasons = mutableListOf<String>()

            // 1) 횡단보도
            if (isCrosswalkWaypoint(wp)) {
                score += 50.0
                reasons.add("횡단보도")
            }

            // 2) 계단
            if (wp.pointType == "STAIRS") {
                score += 100.0
                reasons.add("계단")
            }

            // 3) 차도/자전거도로 통과
            when (wp.roadType) {
                2 -> { score += 80.0; reasons.add("차도 구간") }
                3 -> { score += 40.0; reasons.add("자전거도로") }
            }

            // 4) 복잡한 회전 (직진 제외)
            if (wp.pointType == "TURN" && wp.turnType != 1) {
                score += 10.0
                reasons.add("방향 전환")
            }

            // 5) 구간 거리가 짧으면 연속 이벤트 → 추가 위험
            if (wp.distance in 1..15 && reasons.isNotEmpty()) {
                score += 20.0
                reasons.add("짧은 구간 연속 이벤트")
            }

            val level = when {
                score >= 80 -> DangerLevel.DANGER
                score >= 30 -> DangerLevel.CAUTION
                else -> DangerLevel.SAFE
            }

            results.add(SegmentRisk(
                fromIndex = i, toIndex = i + 1,
                score = score, dangerLevel = level,
                reasons = reasons, distance = wp.distance
            ))
        }
        return results
    }
}