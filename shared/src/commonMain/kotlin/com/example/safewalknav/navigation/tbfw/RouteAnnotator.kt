package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.tmap.RouteSegment
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
     * waypoint 리스트(+ 선택적으로 segment 리스트)를 분석해 AnnotatedRoute 반환.
     *
     * Stage A — waypoint 간 곡선/회전 분석 (기존 동작):
     *   waypoints 가 3개 미만이면 비교할 segment 가 없어 Stage A 는 건너뛴다.
     *
     * Stage B — LineString 내부 곡선 분석 (신규):
     *   segments 가 비어 있지 않으면 각 RouteSegment 의 폴리라인 좌표를 스캔해
     *   TMap 이 단일 LineString 으로 묶어버린 내부 곡선을 INTERNAL_CURVE 로 검출한다.
     *   기본값 emptyList 이므로 기존 호출자(annotate(waypoints))는 동작이 100% 동일.
     *
     * 두 단계 결과를 합친 뒤 distanceFromStartM 오름차순으로 정렬해 반환.
     */
    fun annotate(
        waypoints: List<Waypoint>,
        segments: List<RouteSegment> = emptyList(),
    ): AnnotatedRoute {
        val annotations = mutableListOf<PathAnnotation>()
        val cumulativeDistances = computeCumulativeDistances(waypoints)

        // ─── Stage A: waypoint 간 분석 (기존 로직, 시그니처/임계값 변경 없음) ───
        if (waypoints.size >= 3) {
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
        }

        // ─── Stage B: 각 RouteSegment 내부 곡선 분석 (신규) ───
        for (segment in segments) {
            if (segment.points.size < 3) continue
            val segDistance = cumulativeDistances.getOrNull(segment.fromWaypointIndex) ?: continue
            val internal = scanInternalCurve(segment, segDistance)
            if (internal != null) {
                annotations.add(internal)
            }
        }

        // Stage A 결과는 이미 waypoint 인덱스 오름차순 = 거리 오름차순.
        // Stage B 가 끼어들 수 있으므로 최종 정렬로 순서를 보장.
        annotations.sortBy { it.distanceFromStartM }

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
     * RouteSegment 의 내부 폴리라인(`segment.points`) 을 슬라이딩 윈도우로 훑어
     * 누적 곡률을 구한다. scanCurve 와 동일한 임계값을 사용하지만 끊는 조건은 없고
     * (전체 segment 가 한 LineString 이므로) 모든 sub-segment 의 delta 를 누적한 뒤
     * 마지막에 한꺼번에 판정한다.
     *
     * @return 임계 통과 시 INTERNAL_CURVE 타입의 PathAnnotation, 아니면 null.
     */
    private fun scanInternalCurve(
        segment: RouteSegment,
        distanceFromStartM: Double,
    ): PathAnnotation? {
        val points = segment.points
        if (points.size < 3) return null

        // 첫 sub-segment(인덱스 0,1,2) 의 부호를 기준. 짧은 구간이라도 일단 sign 만 얻고,
        // 실제 loop 에서는 minSegmentDistanceM 미만이면 skip 하므로 결과 카운트는 0 일 수 있다.
        val b1First = bearing(points[0].lat, points[0].lon, points[1].lat, points[1].lon).toDouble()
        val b2First = bearing(points[1].lat, points[1].lon, points[2].lat, points[2].lon).toDouble()
        val deltaFirst = normalizeAngle(b2First - b1First)
        val sign = if (deltaFirst >= 0) 1.0 else -1.0

        var cumulative = 0.0
        var peak = 0.0
        var sameSignCount = 0
        var totalCount = 0

        for (i in 0 until points.size - 2) {
            val a = points[i]
            val b = points[i + 1]
            val c = points[i + 2]

            val d1 = distanceBetween(a.lat, a.lon, b.lat, b.lon).toDouble()
            val d2 = distanceBetween(b.lat, b.lon, c.lat, c.lon).toDouble()
            if (d1 < config.minSegmentDistanceM || d2 < config.minSegmentDistanceM) continue

            val b1 = bearing(a.lat, a.lon, b.lat, b.lon).toDouble()
            val b2 = bearing(b.lat, b.lon, c.lat, c.lon).toDouble()
            val delta = normalizeAngle(b2 - b1)

            cumulative += delta
            if (abs(delta) > abs(peak)) peak = delta
            totalCount++
            val sameSign = (delta >= 0 && sign > 0) || (delta < 0 && sign < 0)
            if (sameSign) sameSignCount++
        }

        if (totalCount == 0) return null

        val consistencyRatio = sameSignCount.toDouble() / totalCount
        if (abs(cumulative) < config.curveCumulativeThresholdDeg) return null
        if (consistencyRatio < config.curveSignConsistencyRatio) return null

        val direction = if (cumulative >= 0) TurnDirection.RIGHT else TurnDirection.LEFT
        val partial = PathAnnotation(
            startWaypointIndex = segment.fromWaypointIndex,
            endWaypointIndex = segment.toWaypointIndex,
            type = PathSegmentType.INTERNAL_CURVE,
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
