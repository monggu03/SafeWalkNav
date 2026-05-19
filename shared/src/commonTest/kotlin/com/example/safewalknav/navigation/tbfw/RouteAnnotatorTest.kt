package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.tmap.Waypoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RouteAnnotator 단위 테스트.
 *
 * 좌표 설계:
 *   서울 시청 부근 (37.5666, 126.9784) 에서 작은 격자로 waypoint 를 배치한다.
 *   - 위도 1m  ≈ 0.0000090°
 *   - 경도 1m  ≈ 0.0000114° (위도 37.5666° 기준)
 *   bearing 함수가 deg 단위 입력을 받기 때문에 모든 좌표를 명시적으로 계산한다.
 *
 * 검증 항목 (가이드 Task 4 매핑):
 *   1. 빈 / 1~2 waypoint → 빈 annotation
 *   2. 직선 → 빈 annotation
 *   3. 단일 90° 회전 → SHARP_TURN
 *   4. 여러 same-sign 곡선 → CURVE
 *   5. 짧은 구간(2m) 30° 변화 → 무시
 *   6. 회전 직후 곡선 → 두 개의 annotation
 *   7. 부호 섞임/누적 미달 → annotation 없음
 *   8. normalizeAngle 정규화
 */
class RouteAnnotatorTest {

    private val annotator = RouteAnnotator(NavigatorConfig())

    // ─── 좌표 헬퍼 ───

    private val baseLat = 37.5666
    private val baseLon = 126.9784
    // 위도 37.5666° 기준
    private val mPerLat = 1.0 / 110_540.0      // ≈ 0.00000905
    private val mPerLon = 1.0 / 87_000.0       // ≈ 0.00001149

    /**
     * (north_m, east_m) 오프셋을 GPS 좌표로 변환해 Waypoint 생성.
     */
    private fun wp(northM: Double, eastM: Double, type: String = "TURN"): Waypoint = Waypoint(
        lat = baseLat + northM * mPerLat,
        lon = baseLon + eastM * mPerLon,
        turnType = 0,
        description = "test",
        distance = 0,
        roadType = 0,
        pointType = type,
    )

    /**
     * (북쪽으로 양수 = 위쪽) bearing/distance 시작점에서 (북=0, 동=90, 남=180, 서=270) 으로
     * angleDeg 만큼 회전한 방향으로 distM 만큼 이동한 새 점 좌표.
     */
    private fun moveByBearing(
        fromLat: Double, fromLon: Double, bearingDeg: Double, distM: Double,
    ): Pair<Double, Double> {
        val rad = bearingDeg * PI / 180.0
        val northM = cos(rad) * distM   // 북쪽 성분
        val eastM = sin(rad) * distM    // 동쪽 성분
        return (fromLat + northM * mPerLat) to (fromLon + eastM * mPerLon)
    }

    /**
     * 시작 좌표에서 일련의 (bearing, distance) 명령을 따라가며 waypoint 시퀀스 생성.
     * 처음 점은 시작 좌표 그대로 들어간다.
     */
    private fun makePath(
        steps: List<Pair<Double, Double>>,
        startLat: Double = baseLat,
        startLon: Double = baseLon,
    ): List<Waypoint> {
        val out = mutableListOf<Waypoint>()
        var lat = startLat
        var lon = startLon
        out.add(Waypoint(lat, lon, 0, "test", 0, 0, "TURN"))
        for ((b, d) in steps) {
            val (nLat, nLon) = moveByBearing(lat, lon, b, d)
            lat = nLat
            lon = nLon
            out.add(Waypoint(lat, lon, 0, "test", 0, 0, "TURN"))
        }
        return out
    }

    // ─── 1. 빈 / 짧은 경로 ───

    @Test
    fun `empty waypoints yields empty annotations`() {
        val result = annotator.annotate(emptyList())
        assertTrue(result.annotations.isEmpty())
        assertTrue(result.waypoints.isEmpty())
    }

    @Test
    fun `single waypoint yields empty annotations`() {
        val result = annotator.annotate(listOf(wp(0.0, 0.0)))
        assertTrue(result.annotations.isEmpty())
    }

    @Test
    fun `two waypoints yields empty annotations`() {
        val result = annotator.annotate(listOf(wp(0.0, 0.0), wp(0.0, 10.0)))
        assertTrue(result.annotations.isEmpty())
    }

    // ─── 2. 직선 ───

    @Test
    fun `pure straight line yields no annotations`() {
        // 5개 waypoint, 모두 동쪽으로 10m 간격
        val path = makePath(listOf(
            90.0 to 10.0,
            90.0 to 10.0,
            90.0 to 10.0,
            90.0 to 10.0,
        ))
        val result = annotator.annotate(path)
        assertTrue(result.annotations.isEmpty(),
            "straight path should not produce annotations, got: ${result.annotations}")
    }

    // ─── 3. 단일 회전 ───

    @Test
    fun `single 90 degree right turn yields SHARP_TURN`() {
        // 동쪽으로 10m → 남쪽(=동에서 90° 우회전)으로 10m
        // bearing: 90 → 180, delta = +90
        val path = makePath(listOf(
            90.0 to 15.0,
            90.0 to 15.0,    // 직진 한 번 더 (3개 점만 있으면 corner 1개만 잡힘)
            180.0 to 15.0,
            180.0 to 15.0,
        ))
        val result = annotator.annotate(path)
        assertEquals(1, result.annotations.size)
        val ann = result.annotations[0]
        assertEquals(PathSegmentType.SHARP_TURN, ann.type)
        assertEquals(TurnDirection.RIGHT, ann.direction)
        assertTrue(ann.peakAngle in 80.0..100.0, "peakAngle ${ann.peakAngle} ~ 90")
    }

    @Test
    fun `single 45 degree left turn yields TURN to LEFT`() {
        // 북쪽 → 북서(45° 좌회전), bearing 0 → 315, delta = -45
        val path = makePath(listOf(
            0.0 to 15.0,
            0.0 to 15.0,
            315.0 to 15.0,
            315.0 to 15.0,
        ))
        val result = annotator.annotate(path)
        assertEquals(1, result.annotations.size)
        val ann = result.annotations[0]
        assertEquals(PathSegmentType.TURN, ann.type)
        assertEquals(TurnDirection.LEFT, ann.direction)
    }

    // ─── 4. 곡선 (cumulative) ───

    @Test
    fun `gentle curve from same-sign deltas yields CURVE`() {
        // 4개의 +12° 변화 → 누적 48° → CURVE
        // bearing 시퀀스: 0, 12, 24, 36, 48 → 각 단계 각도 변화 +12°
        val path = makePath(listOf(
            0.0 to 15.0,
            12.0 to 15.0,
            24.0 to 15.0,
            36.0 to 15.0,
            48.0 to 15.0,
        ))
        val result = annotator.annotate(path)
        assertTrue(result.annotations.isNotEmpty(), "should detect curve")
        val curveAnn = result.annotations.first()
        assertTrue(
            curveAnn.type == PathSegmentType.CURVE || curveAnn.type == PathSegmentType.SLIGHT_CURVE,
            "expected CURVE/SLIGHT_CURVE, got ${curveAnn.type}",
        )
        assertEquals(TurnDirection.RIGHT, curveAnn.direction)
        assertTrue(curveAnn.totalAngle > 30.0, "cumulative ${curveAnn.totalAngle}")
    }

    // ─── 5. 짧은 구간 ───

    @Test
    fun `short segment is skipped from angle judgment`() {
        // 가운데 segment 가 1m (< minSegmentDistanceM) — 30° 변화여도 무시되어야 함.
        // path: 동쪽 15m → 동남(135°) 1m → 동남쪽 15m
        val path = makePath(listOf(
            90.0 to 15.0,
            135.0 to 1.0,    // 짧은 segment
            135.0 to 15.0,
        ))
        val result = annotator.annotate(path)
        // 첫 corner 는 짧은 d2 때문에 건너뜀, 두 번째는 짧은 d1 때문에 건너뜀.
        // 결과적으로 어떤 annotation 도 안 생겨야 함.
        assertTrue(result.annotations.isEmpty(),
            "annotations from short-segment angles should be skipped, got: ${result.annotations}")
    }

    // ─── 6. 회전 직후 곡선 ───

    @Test
    fun `turn followed by curve yields two annotations`() {
        // 동쪽 → 남쪽 (90° 회전) → 남쪽으로 가다가 살짝씩 좌측 곡선
        // bearing: 90, 90, 180, 168, 156, 144, 132 → corner1 +90, corner2~5 -12씩
        val path = makePath(listOf(
            90.0 to 15.0,
            90.0 to 15.0,
            180.0 to 15.0,   // +90 turn
            168.0 to 15.0,   // -12 (좌측 곡선 시작)
            156.0 to 15.0,   // -12
            144.0 to 15.0,   // -12
            132.0 to 15.0,   // -12
        ))
        val result = annotator.annotate(path)
        assertTrue(result.annotations.size >= 2,
            "expected turn + curve, got ${result.annotations.size}: ${result.annotations}")
        val first = result.annotations[0]
        val second = result.annotations[1]
        assertEquals(PathSegmentType.SHARP_TURN, first.type)
        assertEquals(TurnDirection.RIGHT, first.direction)
        assertTrue(
            second.type == PathSegmentType.CURVE || second.type == PathSegmentType.SLIGHT_CURVE,
            "second should be a curve, got ${second.type}",
        )
        assertEquals(TurnDirection.LEFT, second.direction)
    }

    // ─── 7. 누적 미달 / 부호 섞임 ───

    @Test
    fun `single near-noise delta surrounded by sub-noise yields no annotation`() {
        // 동쪽 → 약간 우측(+15°) → 다시 거의 직진 — 누적 15°
        // bearing 시퀀스: 90, 105, 105, 105 → deltas: +15, 0, 0
        // single delta 15° 는 noise(10) 초과지만 turn(30) 미만 → 곡선 스캔 진입.
        // 스캔 결과 누적 +15 < 30 → CURVE 미발생.
        val path = makePath(listOf(
            90.0 to 15.0,
            105.0 to 15.0,
            105.0 to 15.0,
            105.0 to 15.0,
        ))
        val result = annotator.annotate(path)
        assertTrue(result.annotations.isEmpty(),
            "single 15° delta should not become a curve, got: ${result.annotations}")
    }

    @Test
    fun `mixed-sign deltas above noise yields no curve`() {
        // bearing: 90, 105, 90, 105, 90 → deltas: +15, -15, +15, -15
        // 첫 +15 가 곡선 스캔 진입, 두 번째 -15 (반대 부호 above noise) 에서 끊김.
        // 누적 +15 < 30 → CURVE 미발생.
        val path = makePath(listOf(
            90.0 to 15.0,
            105.0 to 15.0,
            90.0 to 15.0,
            105.0 to 15.0,
            90.0 to 15.0,
        ))
        val result = annotator.annotate(path)
        // 곡선은 만들어지지 않아야 한다. 단, 회전(>=30°) 도 없어야 한다.
        assertTrue(
            result.annotations.none { it.type != PathSegmentType.STRAIGHT },
            "mixed-sign signal should not yield curve/turn, got: ${result.annotations}",
        )
    }

    // ─── 8. normalizeAngle ───

    @Test
    fun `normalizeAngle wraps positive overflow`() {
        assertEquals(-10.0, RouteAnnotator.normalizeAngle(350.0), 1e-9)
    }

    @Test
    fun `normalizeAngle wraps negative overflow`() {
        assertEquals(170.0, RouteAnnotator.normalizeAngle(-190.0), 1e-9)
    }

    @Test
    fun `normalizeAngle is identity within range`() {
        assertEquals(45.0, RouteAnnotator.normalizeAngle(45.0), 1e-9)
        assertEquals(-45.0, RouteAnnotator.normalizeAngle(-45.0), 1e-9)
        assertEquals(180.0, RouteAnnotator.normalizeAngle(180.0), 1e-9)
    }

    // ─── 9. AnnotationMessage 내용 검증 ───

    @Test
    fun `turn annotation has non-empty announceMessage`() {
        val path = makePath(listOf(
            90.0 to 15.0,
            90.0 to 15.0,
            180.0 to 15.0,
            180.0 to 15.0,
        ))
        val result = annotator.annotate(path)
        assertTrue(result.annotations.first().announceMessage.isNotBlank())
    }

    // ─── 10. distanceFromStartM 누적 거리 ───

    @Test
    fun `distanceFromStartM tracks cumulative distance`() {
        // 직진 30m 후 회전 — annotation.distanceFromStartM 는 약 30m 근처여야 함.
        val path = makePath(listOf(
            90.0 to 15.0,    // wp1: 15m from start
            90.0 to 15.0,    // wp2: 30m from start
            180.0 to 15.0,   // wp3: 45m from start (회전 발생 시점은 wp2)
            180.0 to 15.0,
        ))
        val result = annotator.annotate(path)
        assertEquals(1, result.annotations.size)
        val ann = result.annotations[0]
        // startWaypointIndex 는 회전이 감지되는 직전 인덱스. cumulativeDistance 는 거기까지의 거리.
        assertTrue(
            ann.distanceFromStartM in 10.0..40.0,
            "distanceFromStartM ${ann.distanceFromStartM} expected ~15-30m",
        )
    }
}
