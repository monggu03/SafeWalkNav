package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.tmap.LatLng
import com.example.safewalknav.navigation.tmap.RouteSegment
import com.example.safewalknav.navigation.tmap.Waypoint
import com.example.safewalknav.navigation.geo.bearing
import com.example.safewalknav.navigation.geo.computeCumulativeDistances
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
    private enum class VirtualWaypointRejectReason {
        angleTooSmall,
        distanceTooShort,
        tooCloseToExistingWaypoint,
        tooCloseToPreviousVirtualWaypoint,
        duplicateCoordinate,
        outsideRoute,
        unknown,
    }

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
                        val dir = if (delta >= 0) "RIGHT" else "LEFT"
                        println("[Annotator IN ] idx=$i prev=(${a.lat},${a.lon}) " +
                                "curr=(${b.lat},${b.lon}) next=(${c.lat},${c.lon})")
                        println("[Annotator TURN] idx=$i b1=$b1 b2=$b2 delta=$delta direction=$dir")
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
                        val cumOk = abs(curve.cumulative) + ANGLE_EPSILON_DEG >= config.curveCumulativeThresholdDeg
                        if (sigOk && cumOk) {
                            val dir = if (curve.cumulative >= 0) "RIGHT" else "LEFT"
                            println("[Annotator IN  ] idx=$i prev=(${a.lat},${a.lon}) " +
                                    "curr=(${b.lat},${b.lon}) next=(${c.lat},${c.lon})")
                            println("[Annotator CURVE] idx=$i b1=$b1 b2=$b2 delta=$delta " +
                                    "cumulative=${curve.cumulative} endIdx=${curve.endIdx} direction=$dir")
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
        for ((segIdx, segment) in segments.withIndex()) {
            println("[Stage B] segIdx=$segIdx wpRange=[${segment.fromWaypointIndex}..${segment.toWaypointIndex}] " +
                    "points.size=${segment.points.size} → scanInternalCurve 호출")
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

    /**
     * 하이브리드 분석 — waypoints 기반 분석 결과에 routePoints 기반 보조 결과를 합친다.
     *
     * 동작:
     *   1. 기존 annotate(waypoints) 로 1차 결과 생성
     *   2. annotation 이 없는 waypoint 구간(= "직진" 판정 구간) 추출
     *   3. 그 구간 안에 속하는 routePoints 만 골라 누적 곡률 검사
     *   4. 임계값 초과 시 추가 PathAnnotation 생성하여 1차 결과에 병합
     *
     * 보조 검사 임계값:
     *   - 최소 구간 길이: minSegmentDistanceM * 5 (즉 15m). 너무 짧으면 노이즈 가능.
     *   - 누적 각도: curveCumulativeThresholdDeg (30°) 그대로 사용
     *   - 부호 일관성: curveSignConsistencyRatio (0.75) 그대로 사용
     *
     * @param waypoints TMap 응답의 waypoint 리스트
     * @param routePoints TMap 응답의 폴리라인 좌표 (촘촘한 점들)
     */
    fun annotateHybrid(
        waypoints: List<Waypoint>,
        routePoints: List<LatLng>,
    ): AnnotatedRoute {
        // 1. 기존 waypoint 기반 분석
        val primary = annotate(waypoints)
        if (waypoints.size < 2 || routePoints.size < 3) return primary

        // 2. 각 waypoint pair([i→i+1]) 단위로 routePoints 내부 곡선을 보조 검출.
        val cumulativeDistances = computeCumulativeDistances(waypoints)
        val pairSupplementary = mutableListOf<PathAnnotation>()
        for (i in 0 until waypoints.size - 1) {
            val curveAnn = detectCurveInRoutePoints(
                waypoints, routePoints, i, i + 1, cumulativeDistances,
            )
            if (curveAnn != null) {
                pairSupplementary.add(curveAnn)
            }
        }

        // 3. annotation 이 없는 waypoint 구간 추출
        //    각 waypoint 가 어느 annotation 에 포함되는지 표시
        val coveredWaypointIndices = mutableSetOf<Int>()
        for (ann in primary.annotations) {
            for (i in ann.startWaypointIndex..ann.endWaypointIndex) {
                coveredWaypointIndices.add(i)
            }
        }

        // 기존 직진 구간(연속된 비-cover waypoint 들) 단위 routePoints 검사 유지.
        val existingSupplementary = mutableListOf<PathAnnotation>()
        var i = 0
        while (i < waypoints.size - 1) {
            // 직진 구간 시작 찾기
            if (i in coveredWaypointIndices) { i++; continue }

            // 직진 구간 끝 찾기
            var end = i
            while (end + 1 < waypoints.size && (end + 1) !in coveredWaypointIndices) {
                end++
            }

            // i ~ end 사이의 routePoints 만 추출해 검사
            if (end > i) {
                val curveAnn = detectCurveInRoutePoints(
                    waypoints, routePoints, i, end, cumulativeDistances,
                )
                if (curveAnn != null) {
                    existingSupplementary.add(curveAnn)
                }
            }
            i = end + 1
        }

        // 4. 1차 + 기존 보조 + pair 보조 결과 병합.
        // pair 는 상위 우선순위 annotation range 에 완전히 포함될 때만 제거한다.
        val higherPriority = primary.annotations + existingSupplementary
        val filteredPairs = pairSupplementary.filterNot { pair ->
            higherPriority.any { higher -> containsRange(higher, pair) }
        }
        val merged = (higherPriority + filteredPairs)
            .sortedBy { it.startWaypointIndex }
        return AnnotatedRoute(waypoints, merged)
    }

    /**
     * waypoint[startIdx] ~ waypoint[endIdx] 사이에 속한 routePoints 들을 모아
     * 누적 곡률을 검사한다. 임계값 초과 시 PathAnnotation 반환.
     */
    private fun detectCurveInRoutePoints(
        waypoints: List<Waypoint>,
        routePoints: List<LatLng>,
        startIdx: Int,
        endIdx: Int,
        cumulativeDistances: List<Double>,
    ): PathAnnotation? {
        val startWp = waypoints[startIdx]
        val endWp = waypoints[endIdx]

        // 이 구간 안에 들어가는 routePoints 추출
        //   기준: 시작 waypoint 에 가장 가까운 routePoint 부터 끝 waypoint 에 가장 가까운 routePoint 까지
        val startRpIdx = closestRoutePointIndex(routePoints, startWp.lat, startWp.lon)
        val endRpIdx = closestRoutePointIndex(routePoints, endWp.lat, endWp.lon)
        val isPair = endIdx == startIdx + 1
        val sliceCount = if (endRpIdx >= startRpIdx) endRpIdx - startRpIdx + 1 else 0
        if (endRpIdx - startRpIdx < 2) {
            if (isPair) logPairCurve(startIdx, sliceCount, 0.0, "SKIP(slice<3)")
            return null
        }

        // 구간 길이 체크 (15m 이상만)
        var segmentDist = 0.0
        for (j in startRpIdx until endRpIdx) {
            segmentDist += distanceBetween(
                routePoints[j].lat, routePoints[j].lon,
                routePoints[j + 1].lat, routePoints[j + 1].lon
            ).toDouble()
        }
        if (segmentDist < config.minSegmentDistanceM * 5) {
            if (isPair) logPairCurve(startIdx, sliceCount, 0.0, "SKIP(distance<${config.minSegmentDistanceM * 5}m)")
            return null
        }

        // routePoints 누적 곡률 계산
        var cumulative = 0.0
        var peak = 0.0
        var sameSignCount = 0
        var totalCount = 0
        var sign = 0.0
        var curveStartRpIdx = -1
        var curveEndRpIdx = -1

        for (j in startRpIdx until endRpIdx - 1) {
            val a = routePoints[j]
            val b = routePoints[j + 1]
            val c = routePoints[j + 2]
            val d1 = distanceBetween(a.lat, a.lon, b.lat, b.lon).toDouble()
            val d2 = distanceBetween(b.lat, b.lon, c.lat, c.lon).toDouble()
            if (d1 < config.minSegmentDistanceM || d2 < config.minSegmentDistanceM) continue

            val b1 = bearing(a.lat, a.lon, b.lat, b.lon).toDouble()
            val b2 = bearing(b.lat, b.lon, c.lat, c.lon).toDouble()
            val delta = normalizeAngle(b2 - b1)

            if (abs(delta) < config.noiseAngleThresholdDeg) continue

            if (curveStartRpIdx < 0) curveStartRpIdx = j
            curveEndRpIdx = j + 2

            if (sign == 0.0) sign = if (delta >= 0) 1.0 else -1.0
            val isSameSign = (delta >= 0 && sign > 0) || (delta < 0 && sign < 0)
            cumulative += delta
            if (abs(delta) > abs(peak)) peak = delta
            totalCount++
            if (isSameSign) sameSignCount++
        }

        if (totalCount == 0) {
            if (isPair) logPairCurve(startIdx, sliceCount, cumulative, "REJECTED(totalCount==0)")
            return null
        }
        val ratio = sameSignCount.toDouble() / totalCount
        if (ratio < config.curveSignConsistencyRatio) {
            if (isPair) logPairCurve(startIdx, sliceCount, cumulative, "REJECTED(consistency=$ratio)")
            return null
        }
        if (abs(cumulative) + ANGLE_EPSILON_DEG < config.curveCumulativeThresholdDeg) {
            if (isPair) logPairCurve(startIdx, sliceCount, cumulative, "REJECTED(cumulative<${config.curveCumulativeThresholdDeg}°)")
            return null
        }
        if (isPair) logPairCurve(startIdx, sliceCount, cumulative, "PASS rpRange=$curveStartRpIdx→$curveEndRpIdx")

        val dir = if (cumulative >= 0) "RIGHT" else "LEFT"
        println("[Annotator RP-CURVE] startIdx=$startIdx endIdx=$endIdx " +
                "startWp=(${startWp.lat},${startWp.lon}) endWp=(${endWp.lat},${endWp.lon}) " +
                "cumulative=$cumulative peak=$peak direction=$dir " +
                "rpRange=$curveStartRpIdx→$curveEndRpIdx")

        // 보조 annotation 생성 (CURVE 로 분류)
        val partial = PathAnnotation(
            startWaypointIndex = startIdx,
            endWaypointIndex = endIdx,
            type = if (abs(cumulative) < config.slightThresholdDeg + 5.0)
                PathSegmentType.SLIGHT_CURVE else PathSegmentType.CURVE,
            direction = if (cumulative >= 0) TurnDirection.RIGHT else TurnDirection.LEFT,
            totalAngle = cumulative,
            peakAngle = peak,
            distanceFromStartM = cumulativeDistances.getOrElse(startIdx) { 0.0 },
            announceMessage = "",
            startRoutePointIndex = curveStartRpIdx,
            endRoutePointIndex = curveEndRpIdx,
        )
        return partial.copy(announceMessage = MessageBuilder.buildAnnotationAnnounce(partial))
    }

    private fun containsRange(
        outer: PathAnnotation,
        inner: PathAnnotation,
    ): Boolean =
        outer.startWaypointIndex <= inner.startWaypointIndex &&
                outer.endWaypointIndex >= inner.endWaypointIndex

    private fun logPairCurve(
        index: Int,
        sliceCount: Int,
        cumulative: Double,
        verdict: String,
    ) {
        println("[PairCurve] i=$index sliceCount=$sliceCount cumulative=$cumulative° verdict=$verdict")
    }

    private fun closestRoutePointIndex(
        routePoints: List<LatLng>,
        lat: Double, lon: Double,
    ): Int {
        var minDist = Double.MAX_VALUE
        var idx = 0
        for ((i, p) in routePoints.withIndex()) {
            val d = distanceBetween(p.lat, p.lon, lat, lon).toDouble()
            if (d < minDist) { minDist = d; idx = i }
        }
        return idx
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
        println("[InternalCurve IN] seg(from=${segment.fromWaypointIndex},to=${segment.toWaypointIndex}) " +
                "points.size=${points.size} " +
                "first=(${points.firstOrNull()?.lat},${points.firstOrNull()?.lon}) " +
                "last=(${points.lastOrNull()?.lat},${points.lastOrNull()?.lon})")
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
            if (d1 < config.minSegmentDistanceM || d2 < config.minSegmentDistanceM) {
                println("[InternalCurve STEP] i=$i d1=${d1}m d2=${d2}m SKIP(d<minSeg=${config.minSegmentDistanceM}m)")
                continue
            }

            val b1 = bearing(a.lat, a.lon, b.lat, b.lon).toDouble()
            val b2 = bearing(b.lat, b.lon, c.lat, c.lon).toDouble()
            val delta = normalizeAngle(b2 - b1)

            cumulative += delta
            if (abs(delta) > abs(peak)) peak = delta
            totalCount++
            val sameSign = (delta >= 0 && sign > 0) || (delta < 0 && sign < 0)
            if (sameSign) sameSignCount++
            println("[InternalCurve STEP] i=$i d1=${d1}m d2=${d2}m PASS delta=${delta}° " +
                    "sameSign=$sameSign cumulative=${cumulative}° totalCount=$totalCount")
        }

        if (totalCount == 0) {
            println("[InternalCurve OUT] seg(from=${segment.fromWaypointIndex},to=${segment.toWaypointIndex}) " +
                    "totalCount=0 cumulative=${cumulative}° " +
                    "vs threshold cum>=${config.curveCumulativeThresholdDeg}° consistency>=${config.curveSignConsistencyRatio} " +
                    "→ REJECTED(totalCount==0, all sub-segments filtered by minSeg)")
            return null
        }

        val consistencyRatio = sameSignCount.toDouble() / totalCount
        val cumOk = abs(cumulative) + ANGLE_EPSILON_DEG >= config.curveCumulativeThresholdDeg
        val ratioOk = consistencyRatio >= config.curveSignConsistencyRatio
        val verdict = when {
            !cumOk && !ratioOk -> "REJECTED(cumulative miss & consistency miss)"
            !cumOk -> "REJECTED(cumulative miss)"
            !ratioOk -> "REJECTED(consistency miss)"
            else -> "PASS"
        }
        println("[InternalCurve OUT] seg(from=${segment.fromWaypointIndex},to=${segment.toWaypointIndex}) " +
                "totalCount=$totalCount cumulative=${cumulative}° consistency=$consistencyRatio " +
                "vs threshold cum>=${config.curveCumulativeThresholdDeg}° consistency>=${config.curveSignConsistencyRatio} " +
                "→ $verdict")
        if (abs(cumulative) + ANGLE_EPSILON_DEG < config.curveCumulativeThresholdDeg) return null
        if (consistencyRatio < config.curveSignConsistencyRatio) return null

        val direction = if (cumulative >= 0) TurnDirection.RIGHT else TurnDirection.LEFT
        println("[Annotator INT-CURVE] segWp=${segment.fromWaypointIndex}->${segment.toWaypointIndex} " +
                "first=(${points.first().lat},${points.first().lon}) " +
                "last=(${points.last().lat},${points.last().lon}) " +
                "cumulative=$cumulative peak=$peak direction=${direction.name}")
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
     * 곡선 annotation 구간에 가상 waypoint 를 삽입한 확장 리스트를 반환한다.
     *
     * 동작 (2026-05-25 변경):
     *   - 원본 waypoints 사이에서 startWaypointIndex..endWaypointIndex 가 곡선 구간이면
     *     해당 waypoint 쌍에 대응하는 **polyline (routePoints) 위**에
     *     [NavigatorConfig.virtualWaypointSpacingM] 누적 거리마다 가상점을 찍는다.
     *   - 직선 lerp 이 아니라 polyline 을 따라가므로 가상점이 실제 도로 위에 정확히 놓인다.
     *   - 각 가상점은 `sourceRoutePointIdx` / `bearingToNext` / `curveDirection` 메타데이터 보유
     *   - 원본 waypoint 는 모두 그대로 보존
     *
     * 대상 타입: CURVE / SLIGHT_CURVE / INTERNAL_CURVE.
     * TURN 류는 한 지점에서 급격히 꺾이므로 보간 의미가 없어 제외.
     *
     * @param routePoints TMap 응답의 폴리라인 좌표. 비어 있으면 가상점이 한 개도 생성되지 않는다.
     */
    fun expandWithVirtualWaypoints(
        annotated: AnnotatedRoute,
        routePoints: List<LatLng>,
    ): List<Waypoint> {
        val original = annotated.waypoints
        if (original.isEmpty()) return emptyList()
        println("[VW-DEBUG] originalWaypoints.count=${original.count()}")
        println("[VW-DEBUG] routePoints.count=${routePoints.count()}")

        // annotation 에서 curve 구간만 추출 (direction 과 routePoint subrange 도 같이 보존)
        data class CurveSpan(
            val waypointRange: IntRange,
            val direction: String?,
            val routePointRange: IntRange?,
        )
        val curveSpans: List<CurveSpan> = annotated.annotations
            .filter {
                it.type == PathSegmentType.CURVE
                        || it.type == PathSegmentType.SLIGHT_CURVE
                        || it.type == PathSegmentType.INTERNAL_CURVE
            }
            .map {
                val dir = if (it.direction == TurnDirection.NONE) null else it.direction.name
                val rpRange = if (it.startRoutePointIndex >= 0 &&
                    it.endRoutePointIndex > it.startRoutePointIndex
                ) {
                    it.startRoutePointIndex..it.endRoutePointIndex
                } else {
                    null
                }
                CurveSpan(it.startWaypointIndex..it.endWaypointIndex, dir, rpRange)
            }

        val expanded = mutableListOf<Waypoint>()
        for (i in original.indices) {
            expanded.add(original[i])
            if (i + 1 < original.size) {
                val explicitCurve = curveSpans.firstOrNull {
                    it.routePointRange != null && i == it.waypointRange.first
                }
                if (explicitCurve?.routePointRange != null) {
                    val rawVirtuals = generateVirtualPointsAlongRoutePointRange(
                        routePoints = routePoints,
                        range = explicitCurve.routePointRange,
                        spacingM = config.virtualWaypointSpacingM,
                        curveDirection = explicitCurve.direction,
                        roadType = original[i].roadType,
                    )
                    val virtuals = filterAndLogVirtualCandidates(
                        candidates = rawVirtuals,
                        routePoints = routePoints,
                        originalWaypoints = original,
                        acceptedWaypoints = expanded,
                        insertIndex = expanded.size,
                        annotationDirection = explicitCurve.direction,
                        fallbackRoutePointRange = explicitCurve.routePointRange,
                        roadType = original[i].roadType,
                    )
                    expanded.addAll(virtuals)
                    continue
                }

                val curve = curveSpans.firstOrNull {
                    it.routePointRange == null && i in it.waypointRange
                }
                if (curve != null) {
                    val rawVirtuals = generateVirtualPointsAlongPolyline(
                        start = original[i],
                        end = original[i + 1],
                        routePoints = routePoints,
                        spacingM = config.virtualWaypointSpacingM,
                        curveDirection = curve.direction,
                    )
                    val virtuals = filterAndLogVirtualCandidates(
                        candidates = rawVirtuals,
                        routePoints = routePoints,
                        originalWaypoints = original,
                        acceptedWaypoints = expanded,
                        insertIndex = expanded.size,
                        annotationDirection = curve.direction,
                        fallbackRoutePointRange = null,
                        roadType = original[i].roadType,
                    )
                    expanded.addAll(virtuals)
                }
            }
        }
        val virtualBefore = expanded.count { it.isVirtual }
        val seenVirtualSourceIndices = mutableSetOf<Int>()
        val deduped = expanded.filter { waypoint ->
            if (!waypoint.isVirtual || waypoint.sourceRoutePointIdx < 0) {
                true
            } else {
                seenVirtualSourceIndices.add(waypoint.sourceRoutePointIdx)
            }
        }
        val virtualAfter = deduped.count { it.isVirtual }
        val duplicateAfter = deduped
            .filter { it.isVirtual && it.sourceRoutePointIdx >= 0 }
            .groupingBy { it.sourceRoutePointIdx }
            .eachCount()
            .count { it.value > 1 }
        println("[VirtualDedup] before=$virtualBefore after=$virtualAfter " +
                "removed=${virtualBefore - virtualAfter} duplicateSourceRPAfter=$duplicateAfter")
        println("[VW-SUMMARY] originalWaypoints=${original.count()}")
        println("[VW-SUMMARY] expandedWaypoints=${deduped.count()}")
        println("[VW-SUMMARY] virtualWaypoints=$virtualAfter")
        return deduped
    }

    private fun filterAndLogVirtualCandidates(
        candidates: List<Waypoint>,
        routePoints: List<LatLng>,
        originalWaypoints: List<Waypoint>,
        acceptedWaypoints: List<Waypoint>,
        insertIndex: Int,
        annotationDirection: String?,
        fallbackRoutePointRange: IntRange?,
        roadType: Int,
    ): List<Waypoint> {
        val mutableCandidates = candidates.toMutableList()
        if (mutableCandidates.isEmpty() && fallbackRoutePointRange != null) {
            val fallback = buildFallbackVirtualWaypoint(
                routePoints = routePoints,
                range = fallbackRoutePointRange,
                curveDirection = annotationDirection,
                roadType = roadType,
            )
            if (fallback != null) {
                mutableCandidates.add(fallback)
            } else {
                println("[VW-REJECT] index=${fallbackRoutePointRange.first} reason=${VirtualWaypointRejectReason.unknown}")
            }
        }

        val accepted = mutableListOf<Waypoint>()
        for ((offset, candidate) in mutableCandidates.withIndex()) {
            val candidateIndex = candidate.sourceRoutePointIdx.takeIf { it >= 0 } ?: (insertIndex + offset)
            val duplicate = (originalWaypoints + acceptedWaypoints + accepted).any {
                sameCoordinate(it.lat, it.lon, candidate.lat, candidate.lon)
            }
            if (duplicate) {
                println("[VW-REJECT] index=$candidateIndex reason=${VirtualWaypointRejectReason.duplicateCoordinate}")
                continue
            }

            val nearestExisting = originalWaypoints.minOfOrNull {
                distanceBetween(candidate.lat, candidate.lon, it.lat, it.lon).toDouble()
            } ?: Double.MAX_VALUE
            if (nearestExisting < config.minDistanceFromExistingWaypointM) {
                println("[VW-REJECT] index=$candidateIndex reason=${VirtualWaypointRejectReason.tooCloseToExistingWaypoint} distance=${nearestExisting}m")
                continue
            }

            val previousVirtuals = (acceptedWaypoints + accepted).filter { it.isVirtual }
            val nearestVirtual = previousVirtuals.minOfOrNull {
                distanceBetween(candidate.lat, candidate.lon, it.lat, it.lon).toDouble()
            } ?: Double.MAX_VALUE
            if (nearestVirtual < config.minDistanceBetweenVirtualWaypointsM) {
                println("[VW-REJECT] index=$candidateIndex reason=${VirtualWaypointRejectReason.tooCloseToPreviousVirtualWaypoint} distance=${nearestVirtual}m")
                continue
            }

            println("[VW-INSERT] index=${insertIndex + accepted.size}")
            println("[VW-INSERT] coordinate=${candidate.lat},${candidate.lon}")
            println("[VW-INSERT] direction=${candidate.curveDirection}")
            println("[VW-INSERT] delta=${candidate.bearingToNext ?: 0.0}")
            println("[VW-INSERT] description=${candidate.description}")
            accepted.add(candidate)
        }
        return accepted
    }

    private fun buildFallbackVirtualWaypoint(
        routePoints: List<LatLng>,
        range: IntRange,
        curveDirection: String?,
        roadType: Int,
    ): Waypoint? {
        if (routePoints.size < 3) return null
        val startIdx = range.first.coerceIn(0, routePoints.lastIndex)
        val endIdx = range.last.coerceIn(0, routePoints.lastIndex)
        if (endIdx - startIdx < 2) {
            println("[VW-REJECT] index=$startIdx reason=${VirtualWaypointRejectReason.outsideRoute}")
            return null
        }

        var bestIndex = -1
        var bestTurn: TurnComputation? = null
        var cumulativeDelta = 0.0
        for (i in startIdx until endIdx - 1) {
            val prev = routePoints[i]
            val current = routePoints[i + 1]
            val next = routePoints[i + 2]
            val turn = computeTurn(prev, current, next, log = false)
            if (abs(turn.delta) < config.noiseAngleThresholdDeg) {
                println("[VW-REJECT] index=$i reason=${VirtualWaypointRejectReason.angleTooSmall} delta=${turn.delta}")
                continue
            }

            cumulativeDelta += turn.delta
            val rawDirection = turn.direction
            val finalDirection = maybeInvert(rawDirection)
            println("[VW-CANDIDATE] index=$i")
            println("[VW-CANDIDATE] prev=${prev.lat},${prev.lon}")
            println("[VW-CANDIDATE] current=${current.lat},${current.lon}")
            println("[VW-CANDIDATE] next=${next.lat},${next.lon}")
            println("[VW-CANDIDATE] delta=${turn.delta}")
            println("[VW-CANDIDATE] cumulativeDelta=$cumulativeDelta")
            println("[VW-CANDIDATE] direction=${finalDirection.name}")

            if (abs(turn.delta) >= config.turnPeakThresholdDeg || abs(cumulativeDelta) >= config.curveCumulativeThresholdDeg) {
                if (bestTurn == null || abs(turn.delta) > abs(bestTurn.delta)) {
                    bestIndex = i + 1
                    bestTurn = turn.copy(direction = finalDirection)
                }
            }
        }

        val turn = bestTurn
        if (bestIndex < 0 || turn == null) {
            println("[VW-REJECT] index=$startIdx reason=${VirtualWaypointRejectReason.angleTooSmall} delta=$cumulativeDelta")
            return null
        }

        val point = routePoints[bestIndex]
        val direction = curveDirection ?: turn.direction.name.takeIf {
            turn.direction == TurnDirection.LEFT || turn.direction == TurnDirection.RIGHT
        }
        val description = when (direction) {
            "RIGHT" -> "길이 오른쪽으로 휘어집니다"
            "LEFT" -> "길이 왼쪽으로 휘어집니다"
            else -> "길이 휘어집니다"
        }
        return Waypoint(
            lat = point.lat,
            lon = point.lon,
            turnType = 0,
            description = description,
            distance = 0,
            roadType = roadType,
            pointType = "VIRTUAL_CURVE",
            isVirtual = true,
            sourceRoutePointIdx = bestIndex,
            curveDirection = direction,
            bearingToNext = turn.delta,
        )
    }

    private fun sameCoordinate(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Boolean =
        abs(aLat - bLat) < 1e-7 && abs(aLon - bLon) < 1e-7

    /**
     * start ↔ end 사이에 해당하는 polyline 슬라이스 위에 누적 거리 spacing 간격으로 가상점을 찍는다.
     *
     * 알고리즘:
     *   1. start / end 가 routePoints 의 어느 인덱스에 가장 가까운지 closestRoutePointIndex 로 찾는다.
     *   2. P[startIdx] → P[startIdx+1] → ... → P[endIdx] 순회하며 segment 길이를 누적한다.
     *   3. `nextTarget` (= 5m, 10m, 15m, ...) 이 현재까지 누적된 거리에 들어오면
     *      해당 segment 안에서의 보간 비율 t 를 풀어 가상점 좌표를 계산한다.
     *   4. 한 segment 안에 여러 spacing 배수가 들어가는 경우 모두 찍는다.
     *
     * 메타데이터:
     *   - `sourceRoutePointIdx = i` (가상점이 놓인 segment 의 시작 인덱스)
     *   - `bearingToNext` = 같은 segment 의 진행 방위각 (한 segment 안의 점들은 같은 값)
     *   - `curveDirection` = annotation 의 direction 그대로 전파
     */
    private fun generateVirtualPointsAlongPolyline(
        start: Waypoint,
        end: Waypoint,
        routePoints: List<LatLng>,
        spacingM: Double,
        curveDirection: String?,
    ): List<Waypoint> {
        if (routePoints.size < 2) return emptyList()

        val startIdx = closestRoutePointIndex(routePoints, start.lat, start.lon)
        val endIdx = closestRoutePointIndex(routePoints, end.lat, end.lon)
        return generateVirtualPointsAlongRoutePointRange(
            routePoints = routePoints,
            range = startIdx..endIdx,
            spacingM = spacingM,
            curveDirection = curveDirection,
            roadType = start.roadType,
        )
    }

    private fun generateVirtualPointsAlongRoutePointRange(
        routePoints: List<LatLng>,
        range: IntRange,
        spacingM: Double,
        curveDirection: String?,
        roadType: Int,
    ): List<Waypoint> {
        if (routePoints.size < 2) return emptyList()
        val startIdx = range.first.coerceIn(0, routePoints.lastIndex)
        val endIdx = range.last.coerceIn(0, routePoints.lastIndex)
        if (endIdx <= startIdx) return emptyList()

        val virtuals = mutableListOf<Waypoint>()
        var accumulated = 0.0
        var nextTarget = spacingM

        for (i in startIdx until endIdx) {
            val p1 = routePoints[i]
            val p2 = routePoints[i + 1]
            val segDist = distanceBetween(p1.lat, p1.lon, p2.lat, p2.lon).toDouble()
            if (segDist == 0.0) continue

            val segBearing = bearing(p1.lat, p1.lon, p2.lat, p2.lon).toDouble()

            while (nextTarget <= accumulated + segDist) {
                val t = (nextTarget - accumulated) / segDist
                val vLat = p1.lat + (p2.lat - p1.lat) * t
                val vLon = p1.lon + (p2.lon - p1.lon) * t
                val description = when (curveDirection) {
                    "RIGHT" -> "길이 오른쪽으로 휘어집니다"
                    "LEFT" -> "길이 왼쪽으로 휘어집니다"
                    else -> "길이 휘어집니다"
                }

                virtuals.add(
                    Waypoint(
                        lat = vLat,
                        lon = vLon,
                        turnType = 0,
                        description = description,
                        distance = 0,
                        roadType = roadType,
                        pointType = "VIRTUAL_CURVE",
                        isVirtual = true,
                        sourceRoutePointIdx = i,
                        curveDirection = curveDirection,
                        bearingToNext = segBearing,
                    )
                )
                nextTarget += spacingM
            }
            accumulated += segDist
        }

        val midpointCandidate = buildInterpolatedVirtualPointAtDistance(
            routePoints = routePoints,
            startIdx = startIdx,
            endIdx = endIdx,
            targetDistanceM = accumulated / 2.0,
            curveDirection = curveDirection,
            roadType = roadType,
        )
        if (midpointCandidate != null) {
            virtuals.add(midpointCandidate)
        }

        println("[VirtualGen] curve(${routePoints[startIdx].lat},${routePoints[startIdx].lon})→(${routePoints[endIdx].lat},${routePoints[endIdx].lon})")
        println("[VirtualGen]   polyline range: $startIdx → $endIdx, generated ${virtuals.size}점")
        virtuals.forEachIndexed { idx, v ->
            println("[VirtualGen]   [$idx] (${v.lat}, ${v.lon}) sourceRP=${v.sourceRoutePointIdx} bearing=${v.bearingToNext}")
        }

        return virtuals
    }

    private fun buildInterpolatedVirtualPointAtDistance(
        routePoints: List<LatLng>,
        startIdx: Int,
        endIdx: Int,
        targetDistanceM: Double,
        curveDirection: String?,
        roadType: Int,
    ): Waypoint? {
        if (targetDistanceM < config.minDistanceFromExistingWaypointM) return null

        var accumulated = 0.0
        for (i in startIdx until endIdx) {
            val p1 = routePoints[i]
            val p2 = routePoints[i + 1]
            val segDist = distanceBetween(p1.lat, p1.lon, p2.lat, p2.lon).toDouble()
            if (segDist == 0.0) continue
            if (targetDistanceM <= accumulated + segDist) {
                val t = (targetDistanceM - accumulated) / segDist
                val vLat = p1.lat + (p2.lat - p1.lat) * t
                val vLon = p1.lon + (p2.lon - p1.lon) * t
                val segBearing = bearing(p1.lat, p1.lon, p2.lat, p2.lon).toDouble()
                val description = when (curveDirection) {
                    "RIGHT" -> "길이 오른쪽으로 휘어집니다"
                    "LEFT" -> "길이 왼쪽으로 휘어집니다"
                    else -> "길이 휘어집니다"
                }
                return Waypoint(
                    lat = vLat,
                    lon = vLon,
                    turnType = 0,
                    description = description,
                    distance = 0,
                    roadType = roadType,
                    pointType = "VIRTUAL_CURVE",
                    isVirtual = true,
                    sourceRoutePointIdx = i,
                    curveDirection = curveDirection,
                    bearingToNext = segBearing,
                )
            }
            accumulated += segDist
        }
        return null
    }

    companion object {
        private const val ANGLE_EPSILON_DEG = 0.5

        /**
         * 각도 차이를 -180 ~ +180 범위로 정규화.
         * bearing2 - bearing1 결과가 350° 처럼 나와도 -10° 로 바꿔 같은 방향임을 인식할 수 있게 한다.
         */
        fun normalizeAngle(deg: Double): Double = normalizedAngle(deg)
    }
}
