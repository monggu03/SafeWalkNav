package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.tmap.Waypoint
import com.example.safewalknav.navigation.geo.bearing
import com.example.safewalknav.navigation.geo.distanceBetween
import kotlin.math.abs

/**
 * TMap 경로 waypoint 리스트를 받아 각 구간을 직진/곡선/회전으로 분류해
 * 사전 음성 안내용 PathAnnotation 묶음을 만든다.
 *
 * 분류 원칙 (NavigatorConfig 의 임계값 기준):
 *   1. 짧은 구간 (< minSegmentDistanceM) 은 GPS/측정 노이즈 가능성이 높아 판단 보류.
 *   2. 단일 waypoint 구간에서 |delta| >= turnPeakThresholdDeg 이면 회전 (peak 우선).
 *   3. 그렇지 않으면 같은 부호로 연속되는 구간을 스캔해 누적값/부호 일관성을 검사,
 *      |누적| >= curveCumulativeThresholdDeg && consistencyRatio >= curveSignConsistencyRatio
 *      이면 곡선으로 묶는다.
 *   4. 그 외엔 직진으로 간주 (annotation 만들지 않음).
 *
 * 강도(slight / 기본 / sharp) 는 절대값 기준:
 *   - slightThresholdDeg 미만 : SLIGHT_*
 *   - sharpThresholdDeg 이상 : SHARP_TURN
 *   - 그 외 : 기본형
 *
 * 모든 announceMessage 는 MessageBuilder.buildAnnotationAnnounce 가 채운다.
 */
class RouteAnnotator(
    private val config: NavigatorConfig = NavigatorConfig(),
) {
    /**
     * waypoint 리스트를 분석해 AnnotatedRoute 반환.
     * waypoint 가 3개 미만이면 비교할 segment 가 없어 빈 annotation 으로 돌려준다.
     */
    fun annotate(waypoints: List<Waypoint>): AnnotatedRoute {
        if (waypoints.size < 3) {
            return AnnotatedRoute(waypoints, emptyList())
        }

        val annotations = mutableListOf<PathAnnotation>()
        val cumulativeDistances = computeCumulativeDistances(waypoints)

        var i = 0
        while (i < waypoints.size - 2) {
            val a = waypoints[i]
            val b = waypoints[i + 1]
            val c = waypoints[i + 2]

            val d1 = distanceBetween(a.lat, a.lon, b.lat, b.lon).toDouble()
            val d2 = distanceBetween(b.lat, b.lon, c.lat, c.lon).toDouble()

            // 짧은 구간 — 각도 판단 자체를 건너뜀 (직진 처리 X, 단순히 다음 인덱스로).
            if (d1 < config.minSegmentDistanceM || d2 < config.minSegmentDistanceM) {
                i++
                continue
            }

            val b1 = bearing(a.lat, a.lon, b.lat, b.lon).toDouble()
            val b2 = bearing(b.lat, b.lon, c.lat, c.lon).toDouble()
            val delta = normalizeAngle(b2 - b1)

            when {
                // 단일 회전 — peak 우선. 곡선 후보보다 먼저 검사.
                abs(delta) >= config.turnPeakThresholdDeg -> {
                    annotations.add(
                        buildTurnAnnotation(
                            startIdx = i,
                            endIdx = i + 1,
                            delta = delta,
                            distanceFromStartM = cumulativeDistances[i],
                        )
                    )
                    i++
                }

                // 곡선 후보 — 노이즈 임계 초과 시 같은 부호로 연속 스캔.
                abs(delta) >= config.noiseAngleThresholdDeg -> {
                    val curve = scanCurve(waypoints, i)
                    val sigOk = curve.consistencyRatio >= config.curveSignConsistencyRatio
                    val cumOk = abs(curve.cumulative) >= config.curveCumulativeThresholdDeg
                    if (sigOk && cumOk) {
                        annotations.add(
                            buildCurveAnnotation(
                                startIdx = i,
                                endIdx = curve.endIdx,
                                cumulative = curve.cumulative,
                                peak = curve.peak,
                                distanceFromStartM = cumulativeDistances[i],
                            )
                        )
                        i = curve.endIdx
                    } else {
                        i++
                    }
                }

                else -> i++  // 직진 (노이즈 수준)
            }
        }

        return AnnotatedRoute(waypoints, annotations)
    }

    /** scanCurve 의 결과 묶음 — 어디까지 묶었는지 + 누적/peak/일관성. */
    private data class CurveScanResult(
        val endIdx: Int,
        val cumulative: Double,
        val peak: Double,
        val consistencyRatio: Double,
    )

    /**
     * startIdx 부터 같은 부호로 연속되는 구간을 묶어 누적 각도를 구한다.
     *
     * 종료 조건:
     *   - 단일 변화량이 turnPeakThresholdDeg 를 넘으면 직전에서 끊는다 (회전이 시작됨).
     *   - 짧은 구간 (< minSegmentDistanceM) 을 만나면 끊는다.
     *   - 반대 부호 변화량이 noiseAngleThresholdDeg 를 초과하면 끊는다.
     *     (그 이하면 노이즈로 보고 무시 — 다만 일관성 카운트에는 반영.)
     */
    private fun scanCurve(
        waypoints: List<Waypoint>,
        startIdx: Int,
    ): CurveScanResult {
        var cumulative = 0.0
        var peak = 0.0
        var sameSignCount = 0
        var totalCount = 0
        var endIdx = startIdx + 1   // 최소한 한 구간은 처리

        // startIdx 의 부호를 기준 부호로 사용.
        val startA = waypoints[startIdx]
        val startB = waypoints[startIdx + 1]
        val startC = waypoints[startIdx + 2]
        val startB1 = bearing(startA.lat, startA.lon, startB.lat, startB.lon).toDouble()
        val startB2 = bearing(startB.lat, startB.lon, startC.lat, startC.lon).toDouble()
        val startDelta = normalizeAngle(startB2 - startB1)
        val sign = if (startDelta >= 0) 1.0 else -1.0

        var i = startIdx
        while (i < waypoints.size - 2) {
            val a = waypoints[i]
            val b = waypoints[i + 1]
            val c = waypoints[i + 2]

            val d1 = distanceBetween(a.lat, a.lon, b.lat, b.lon).toDouble()
            val d2 = distanceBetween(b.lat, b.lon, c.lat, c.lon).toDouble()
            if (d1 < config.minSegmentDistanceM || d2 < config.minSegmentDistanceM) {
                break
            }

            val b1 = bearing(a.lat, a.lon, b.lat, b.lon).toDouble()
            val b2 = bearing(b.lat, b.lon, c.lat, c.lon).toDouble()
            val delta = normalizeAngle(b2 - b1)

            // 단일 변화량이 회전 임계 이상이면 곡선이 아니라 회전이 시작된 것 — 직전에서 멈춘다.
            if (abs(delta) >= config.turnPeakThresholdDeg) break

            val sameSign = (delta >= 0 && sign > 0) || (delta < 0 && sign < 0)
            // 반대 부호인데 노이즈 수준을 넘으면 곡선이 끝난 것 — 끊는다.
            if (!sameSign && abs(delta) > config.noiseAngleThresholdDeg) break

            // 정상 누적
            cumulative += delta
            if (abs(delta) > abs(peak)) peak = delta
            totalCount++
            if (sameSign) sameSignCount++

            endIdx = i + 1
            i++
        }

        val ratio = if (totalCount == 0) 0.0 else sameSignCount.toDouble() / totalCount
        return CurveScanResult(endIdx, cumulative, peak, ratio)
    }

    private fun buildTurnAnnotation(
        startIdx: Int,
        endIdx: Int,
        delta: Double,
        distanceFromStartM: Double,
    ): PathAnnotation {
        val absDelta = abs(delta)
        val type = when {
            absDelta >= config.sharpThresholdDeg -> PathSegmentType.SHARP_TURN
            absDelta >= config.slightThresholdDeg -> PathSegmentType.TURN
            else -> PathSegmentType.SLIGHT_TURN
        }
        val direction = if (delta >= 0) TurnDirection.RIGHT else TurnDirection.LEFT
        val partial = PathAnnotation(
            startWaypointIndex = startIdx,
            endWaypointIndex = endIdx,
            type = type,
            direction = direction,
            totalAngle = delta,
            peakAngle = delta,
            distanceFromStartM = distanceFromStartM,
            announceMessage = "",
        )
        return partial.copy(announceMessage = MessageBuilder.buildAnnotationAnnounce(partial))
    }

    private fun buildCurveAnnotation(
        startIdx: Int,
        endIdx: Int,
        cumulative: Double,
        peak: Double,
        distanceFromStartM: Double,
    ): PathAnnotation {
        val absCum = abs(cumulative)
        // 곡선 강도는 누적 절대값 기준. sharp 곡선은 사실상 회전과 비슷하므로
        // SHARP_TURN 으로 분류하지 않고 CURVE 로 유지한다 (음성 문구도 곡선).
        val type = if (absCum < config.slightThresholdDeg + 5.0) {
            // 30~35° 누적은 시각장애인 입장에서 거의 직진처럼 느껴질 수 있어 slight 로.
            PathSegmentType.SLIGHT_CURVE
        } else {
            PathSegmentType.CURVE
        }
        val direction = if (cumulative >= 0) TurnDirection.RIGHT else TurnDirection.LEFT
        val partial = PathAnnotation(
            startWaypointIndex = startIdx,
            endWaypointIndex = endIdx,
            type = type,
            direction = direction,
            totalAngle = cumulative,
            peakAngle = peak,
            distanceFromStartM = distanceFromStartM,
            announceMessage = "",
        )
        return partial.copy(announceMessage = MessageBuilder.buildAnnotationAnnounce(partial))
    }

    /**
     * waypoint[0]..waypoint[i] 까지의 누적 거리 (m).
     * cumulative[0] = 0, cumulative[i] = sum(distance(j, j+1) for j in 0..i-1).
     */
    private fun computeCumulativeDistances(waypoints: List<Waypoint>): List<Double> {
        if (waypoints.isEmpty()) return emptyList()
        val out = ArrayList<Double>(waypoints.size)
        out.add(0.0)
        var acc = 0.0
        for (i in 1 until waypoints.size) {
            val a = waypoints[i - 1]
            val b = waypoints[i]
            acc += distanceBetween(a.lat, a.lon, b.lat, b.lon).toDouble()
            out.add(acc)
        }
        return out
    }

    companion object {
        /**
         * 각도 차이를 -180 ~ +180 범위로 정규화.
         * bearing2 - bearing1 결과가 350° 처럼 나와도 -10° 로 바꿔 같은 방향임을 인식할 수 있게 한다.
         */
        fun normalizeAngle(deg: Double): Double {
            var result = deg % 360.0
            if (result > 180.0) result -= 360.0
            if (result < -180.0) result += 360.0
            return result
        }
    }
}
