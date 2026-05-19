package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.geo.bearing
import com.example.safewalknav.navigation.geo.distanceBetween
import kotlin.math.abs
import kotlin.math.round

/**
 * RouteAnnotator 의 분류 결과를 사람이 지도와 비교하며 검증할 수 있도록
 * 콘솔에 보기 좋은 포맷으로 찍는다.
 *
 * 출력 형식:
 *   === Route Annotation Log ===
 *   Route: <name>
 *   Total waypoints: N
 *   Total distance: Mm
 *
 *   [Annotations]
 *   #1 waypoints[5..7] type=CURVE dir=RIGHT cumulative=+34.2° peak=+18.1° distFromStart=78.4m
 *   ...
 *
 *   [Segment-by-segment angle deltas]
 *   [0→1] d=12.3m  delta=+0.5°  (직진)
 *   [1→2] d=15.0m  delta=+1.2°  (직진)
 *   [2→3] d=2.1m   delta=+24.0° (짧은 구간, 무시)
 *   ...
 *
 * 임계값 튜닝용 — 실 사용자 평가에서 "이 곡선이 왜 안 잡혔지?" 같은 질문에 답하기 위함.
 */
object RouteAnnotationLogger {

    fun log(
        annotated: AnnotatedRoute,
        routeName: String = "(unnamed)",
        totalDistanceM: Int? = null,
        config: NavigatorConfig = NavigatorConfig(),
    ) {
        val waypoints = annotated.waypoints
        val annotations = annotated.annotations

        println("=== Route Annotation Log ===")
        println("Route: $routeName")
        println("Total waypoints: ${waypoints.size}")
        if (totalDistanceM != null) {
            println("Total distance: ${totalDistanceM}m")
        }
        println("Config: noise=${config.noiseAngleThresholdDeg}° " +
                "peak=${config.turnPeakThresholdDeg}° " +
                "cumulative=${config.curveCumulativeThresholdDeg}° " +
                "minSeg=${config.minSegmentDistanceM}m")

        println()
        println("[Annotations]")
        if (annotations.isEmpty()) {
            println("(none — RouteAnnotator did not detect any curves/turns)")
        } else {
            annotations.forEachIndexed { i, ann ->
                val range = if (ann.startWaypointIndex == ann.endWaypointIndex) {
                    "waypoints[${ann.startWaypointIndex}]"
                } else {
                    "waypoints[${ann.startWaypointIndex}..${ann.endWaypointIndex}]"
                }
                println(
                    "#${(i + 1)} ${range.padEnd(20)} " +
                        "type=${ann.type.name.padEnd(13)} " +
                        "dir=${ann.direction.name.padEnd(5)} " +
                        "cumulative=${fmtSignedDeg(ann.totalAngle)} " +
                        "peak=${fmtSignedDeg(ann.peakAngle)} " +
                        "distFromStart=${fmtDist(ann.distanceFromStartM)}"
                )
                println("    msg: ${ann.announceMessage}")
            }
        }

        println()
        println("[Segment-by-segment angle deltas]")
        if (waypoints.size < 3) {
            println("(too few waypoints to compute deltas)")
        } else {
            for (i in 0 until waypoints.size - 2) {
                val a = waypoints[i]
                val b = waypoints[i + 1]
                val c = waypoints[i + 2]
                val d1 = distanceBetween(a.lat, a.lon, b.lat, b.lon).toDouble()
                val d2 = distanceBetween(b.lat, b.lon, c.lat, c.lon).toDouble()
                val b1 = bearing(a.lat, a.lon, b.lat, b.lon).toDouble()
                val b2 = bearing(b.lat, b.lon, c.lat, c.lon).toDouble()
                val delta = RouteAnnotator.normalizeAngle(b2 - b1)

                val tag = when {
                    d1 < config.minSegmentDistanceM || d2 < config.minSegmentDistanceM ->
                        "짧은 구간, 무시"
                    abs(delta) < config.noiseAngleThresholdDeg ->
                        "직진/노이즈"
                    abs(delta) >= config.turnPeakThresholdDeg ->
                        "회전 (peak)"
                    else ->
                        "곡선 후보"
                }
                println(
                    "[${i}→${i + 1}] d=${fmtDist(d1).padEnd(9)} " +
                        "delta=${fmtSignedDeg(delta).padEnd(9)} ($tag)"
                )
            }
            // 마지막 segment 는 delta 가 정의되지 않지만, 거리 정보는 보여준다.
            val lastIdx = waypoints.size - 2
            val a = waypoints[lastIdx]
            val b = waypoints[lastIdx + 1]
            val d = distanceBetween(a.lat, a.lon, b.lat, b.lon).toDouble()
            println("[${lastIdx}→${lastIdx + 1}] d=${fmtDist(d).padEnd(9)} (마지막 구간)")
        }

        println("============================")
    }

    // ─── format helpers (commonMain — String.format 사용 불가) ───

    private fun fmtSignedDeg(value: Double): String {
        val rounded = round(value * 10) / 10
        val sign = if (value >= 0) "+" else ""
        return "${sign}${rounded}°"
    }

    private fun fmtDist(value: Double): String {
        val rounded = round(value * 10) / 10
        return "${rounded}m"
    }
}
