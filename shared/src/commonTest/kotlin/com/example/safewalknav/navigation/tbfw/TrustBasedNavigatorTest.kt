package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.platform.GpsLocation
import com.example.safewalknav.navigation.tmap.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TrustBasedNavigator 통합 테스트.
 *
 * 좌표 설계 주의사항:
 *   - 위도 37.5666°에서 경도 0.0001° ≈ 8.8m (서울 기준, 적도 11m보다 짧음)
 *   - 같은 좌표 위에 사용자를 두면 bearing이 정의되지 않아 통과 처리 실패
 *   - 사용자는 항상 waypoint 약간 이전(서쪽)에 배치하여 동쪽으로 진행하는 시나리오 구성
 *
 * Waypoint 간격:
 *   - 경도 0.00015 차이 ≈ 13m (NORMAL 통과 거리 8m + 여유)
 *   - 너무 가까우면 W1 통과 시점에 W2까지 거리도 통과 거리 안에 들어가버림
 *
 * 시간 모델:
 *   - 본 테스트는 navigator.update(state, t) 의 t 를 직접 제공해 점프 감지를 결정한다.
 *   - 인접 update 간 시간 간격을 충분히(>=1초) 두면 NORMAL 보행으로 간주된다.
 */
class TrustBasedNavigatorTest {

    // 위도 37.5666°에서 경도 1m ≈ 0.00001137°
    private val metersPerLongitudeDegree = 0.00001137

    /**
     * 테스트용 직선 경로 — 서울 시청에서 동쪽으로 3개 waypoint
     * 각 waypoint 간 경도 0.00015도 ≈ 약 13m
     */
    private fun makeStraightRoute(): List<Waypoint> = listOf(
        Waypoint(
            lat = 37.5666, lon = 126.97840,
            turnType = 0, description = "W1",
            distance = 13, roadType = 0, pointType = "TURN"
        ),
        Waypoint(
            lat = 37.5666, lon = 126.97855,   // W1에서 약 13m 동쪽
            turnType = 0, description = "W2",
            distance = 13, roadType = 0, pointType = "CROSSWALK"
        ),
        Waypoint(
            lat = 37.5666, lon = 126.97870,   // W2에서 약 13m 동쪽
            turnType = 0, description = "W3",
            distance = 0, roadType = 0, pointType = "DESTINATION"
        ),
    )

    /**
     * 정상 GPS 위치 생성 헬퍼.
     */
    private fun goodGps(
        lat: Double,
        lon: Double,
        speed: Float = 1.2f,
        bearing: Float = 90f,    // 동쪽
        accuracy: Float = 5f
    ) = GpsLocation(
        latitude = lat,
        longitude = lon,
        speed = speed,
        bearing = bearing,
        accuracy = accuracy,
        hasAccuracy = true
    )

    /**
     * waypoint 약간 이전(서쪽 N미터)에 사용자를 배치하는 헬퍼.
     * 동쪽으로 진행 중인 시나리오 구성용.
     *
     * 이렇게 해야 사용자→waypoint bearing이 90°(동쪽)으로 나와서
     * heading=90°와 일치 → 통과 조건 만족.
     */
    private fun stateBefore(
        targetLon: Double,
        targetLat: Double = 37.5666,
        offsetMeters: Double = 3.0,
        accuracy: Float = 3f
    ): UserState {
        val offsetDegrees = offsetMeters * metersPerLongitudeDegree
        return UserState(
            location = goodGps(
                lat = targetLat,
                lon = targetLon - offsetDegrees,  // 서쪽으로 offset
                accuracy = accuracy
            ),
            heading = 90f  // 동쪽
        )
    }

    // ─── 기본 동작 ───

    @Test
    fun `생성 직후 첫 update에서 인덱스 0이 유지된다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        // W1 약 20m 서쪽 — 아직 통과 거리 밖
        val state = stateBefore(targetLon = 126.97840, offsetMeters = 20.0)

        val result = navigator.update(state, currentTimeMs = 1_000)

        assertEquals(0, result.currentWaypointIndex)
        assertFalse(result.isFinished)
    }

    @Test
    fun `빈 경로면 처음부터 종료 상태다`() {
        val navigator = TrustBasedNavigator(emptyList())
        val state = UserState(
            location = goodGps(37.5666, 126.9784),
            heading = 90f
        )

        val result = navigator.update(state, currentTimeMs = 1_000)

        assertTrue(result.isFinished)
        assertEquals(MessageBuilder.MSG_ARRIVED_DESTINATION, result.message)
    }

    // ─── 정상 보행 시나리오 ───

    @Test
    fun `정상 GPS와 정방향 보행이면 NORMAL 점프 레벨이 나온다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        val state = stateBefore(targetLon = 126.97840, offsetMeters = 20.0)

        val result = navigator.update(state, currentTimeMs = 1_000)

        assertEquals(GpsJumpLevel.NORMAL, result.jumpLevel)
    }

    @Test
    fun `정상 보행 중에는 GPS 점프 안내가 안 뜬다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        val state = stateBefore(targetLon = 126.97840, offsetMeters = 20.0)

        val result = navigator.update(state, currentTimeMs = 1_000)

        assertFalse(
            result.message.contains("위치 안내가 잠시 어렵습니다"),
            "GPS 점프 안내 메시지가 떴음: ${result.message}",
        )
        assertEquals(JumpGuidanceAction.SILENT, result.guidanceAction)
    }

    // ─── Waypoint 통과 시나리오 ───

    @Test
    fun `W1 근처에 도달하면 통과 처리되고 인덱스가 1이 된다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        // W1 약 3m 서쪽 (8m 통과 거리 안)
        val state = stateBefore(targetLon = 126.97840, offsetMeters = 3.0)

        val result = navigator.update(state, currentTimeMs = 1_000)

        assertTrue(
            result.didPassWaypoint,
            "통과 처리 안 됨. 거리: ${result.distanceToWaypoint}, " +
            "jumpLevel: ${result.jumpLevel}, headingDiff: ${result.headingDiff}"
        )
        assertEquals(1, result.currentWaypointIndex)
    }

    @Test
    fun `통과 후 다음 update에서는 W2를 목표로 한다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())

        // 1차 update — W1 통과 (W1 약 3m 서쪽)
        val first = navigator.update(
            stateBefore(targetLon = 126.97840, offsetMeters = 3.0),
            currentTimeMs = 1_000,
        )
        assertTrue(first.didPassWaypoint, "1차에서 W1 통과 실패")

        // 2차 update — W1과 W2 사이 (W2 약 10m 서쪽). 6초 뒤로 진행해 정상 보행 가정.
        val result = navigator.update(
            stateBefore(targetLon = 126.97855, offsetMeters = 10.0),
            currentTimeMs = 7_000,
        )

        assertEquals(1, result.currentWaypointIndex, "여전히 W2(인덱스 1)가 목표여야 함")
        assertFalse(result.isFinished)
    }

    // ─── GPS 튐 시나리오 (Forward-Only + 점프 감지 핵심) ───

    @Test
    fun `W1 통과 후 GPS가 1초 만에 큰 거리로 튀면 JUMPED 가 되고 인덱스는 감소하지 않는다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())

        // 1단계: W1 통과
        val pass = navigator.update(
            stateBefore(targetLon = 126.97840, offsetMeters = 3.0),
            currentTimeMs = 1_000,
        )
        assertTrue(pass.didPassWaypoint, "1단계에서 W1 통과 실패")
        assertEquals(1, pass.currentWaypointIndex)

        // 2단계: 1초 만에 W1 보다 약 20m 서쪽으로 GPS 튐 — 약 ~23 m/s → JUMPED
        val jumped = navigator.update(
            UserState(
                location = goodGps(
                    lat = 37.5666,
                    lon = 126.97820,  // W1 보다 약 18m 서쪽 (사용자 직전 위치보다 ~21m 떨어짐)
                ),
                heading = 90f
            ),
            currentTimeMs = 2_000,
        )

        // 점프 감지 + Forward-Only — 인덱스 유지
        assertEquals(GpsJumpLevel.JUMPED, jumped.jumpLevel)
        assertEquals(1, jumped.currentWaypointIndex, "Forward-Only가 깨졌음")
    }

    @Test
    fun `JUMPED 가 3초 이상 지속되면 안내 정책이 ANNOUNCE_DEGRADED 를 돌려준다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())

        // 첫 update — NORMAL 시작점
        navigator.update(
            stateBefore(targetLon = 126.97840, offsetMeters = 20.0),
            currentTimeMs = 1_000,
        )
        // 1초 뒤 큰 점프 — JUMPED 진입
        navigator.update(
            UserState(
                location = goodGps(lat = 37.5666, lon = 126.97900),  // 약 60m 동쪽
                heading = 90f,
            ),
            currentTimeMs = 2_000,
        )
        // 또 1초 뒤 — duration = 1s (silent window 안)
        val silent = navigator.update(
            UserState(
                location = goodGps(lat = 37.5666, lon = 126.97960),
                heading = 90f,
            ),
            currentTimeMs = 3_000,
        )
        assertEquals(JumpGuidanceAction.SILENT, silent.guidanceAction)

        // 3초 더 경과해 silent window 초과 → ANNOUNCE_DEGRADED
        val announced = navigator.update(
            UserState(
                location = goodGps(lat = 37.5666, lon = 126.98050),
                heading = 90f,
            ),
            currentTimeMs = 6_000,
        )
        assertEquals(GpsJumpLevel.JUMPED, announced.jumpLevel)
        assertEquals(JumpGuidanceAction.ANNOUNCE_DEGRADED, announced.guidanceAction)
        assertEquals(MessageBuilder.MSG_GPS_DEGRADED, announced.message)
    }

    @Test
    fun `JUMPED 동안은 waypoint 통과 처리 안 한다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())

        // 첫 update — 멀리서 시작
        navigator.update(
            stateBefore(targetLon = 126.97840, offsetMeters = 100.0),
            currentTimeMs = 1_000,
        )
        // 1초 만에 W1 코앞으로 점프 — JUMPED
        val result = navigator.update(
            stateBefore(targetLon = 126.97840, offsetMeters = 1.0),
            currentTimeMs = 2_000,
        )

        assertEquals(GpsJumpLevel.JUMPED, result.jumpLevel)
        assertFalse(result.didPassWaypoint, "JUMPED인데 통과 처리됨")
        assertEquals(0, result.currentWaypointIndex)
    }

    // ─── 종료 시나리오 ───

    @Test
    fun `모든 waypoint 통과하면 도착 메시지가 나온다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())

        // W1 통과 (W1 3m 서쪽)
        val r1 = navigator.update(
            stateBefore(targetLon = 126.97840, offsetMeters = 3.0),
            currentTimeMs = 1_000,
        )
        assertTrue(r1.didPassWaypoint, "W1 통과 실패")

        // W2 통과 (W2 3m 서쪽), 충분한 시간 간격
        val r2 = navigator.update(
            stateBefore(targetLon = 126.97855, offsetMeters = 3.0),
            currentTimeMs = 11_000,
        )
        assertTrue(r2.didPassWaypoint, "W2 통과 실패")

        // W3 통과 (W3 3m 서쪽)
        val r3 = navigator.update(
            stateBefore(targetLon = 126.97870, offsetMeters = 3.0),
            currentTimeMs = 21_000,
        )

        assertTrue(
            r3.isFinished || r3.didPassWaypoint,
            "W3 통과 또는 종료 상태가 아님. 인덱스: ${r3.currentWaypointIndex}"
        )
    }

    @Test
    fun `종료 후 update를 더 호출해도 종료 상태를 유지한다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())

        // 3개 다 통과
        navigator.update(
            stateBefore(targetLon = 126.97840, offsetMeters = 3.0),
            currentTimeMs = 1_000,
        )
        navigator.update(
            stateBefore(targetLon = 126.97855, offsetMeters = 3.0),
            currentTimeMs = 11_000,
        )
        navigator.update(
            stateBefore(targetLon = 126.97870, offsetMeters = 3.0),
            currentTimeMs = 21_000,
        )

        // 그 후 추가 호출 (전혀 다른 위치)
        val result = navigator.update(
            UserState(
                location = goodGps(37.5666, 126.9790),
                heading = 90f
            ),
            currentTimeMs = 31_000,
        )

        assertTrue(result.isFinished)
        assertEquals(MessageBuilder.MSG_ARRIVED_DESTINATION, result.message)
    }

    // ─── heading null 처리 ───

    @Test
    fun `UserState heading이 null이면 GPS bearing이 사용된다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        val state = UserState(
            location = goodGps(
                lat = 37.5666,
                lon = 126.97840 - 20.0 * 0.00001137,  // W1 약 20m 서쪽
                bearing = 90f              // GPS가 동쪽 진행으로 측정
            ),
            heading = null                  // heading 미제공
        )

        val result = navigator.update(state, currentTimeMs = 1_000)

        // GPS bearing이 정방향이므로 headingDiff 가 작게 나와야 한다.
        assertTrue(
            kotlin.math.abs(result.headingDiff) < 15f,
            "heading null인데 headingDiff 큼: ${result.headingDiff}",
        )
    }

    // ─── 횡단보도 zone 안에서는 점프 안내 안 함 ───

    @Test
    fun `횡단보도 zone 안에서는 JUMPED여도 안내 정책이 SILENT다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())

        navigator.update(
            stateBefore(targetLon = 126.97840, offsetMeters = 20.0),
            currentTimeMs = 1_000,
            isInCrosswalkZone = true,
        )
        navigator.update(
            UserState(
                location = goodGps(lat = 37.5666, lon = 126.97900),
                heading = 90f,
            ),
            currentTimeMs = 2_000,
            isInCrosswalkZone = true,
        )
        // 3초 이상 경과해도 SILENT 여야 함
        val result = navigator.update(
            UserState(
                location = goodGps(lat = 37.5666, lon = 126.98000),
                heading = 90f,
            ),
            currentTimeMs = 6_000,
            isInCrosswalkZone = true,
        )

        assertEquals(JumpGuidanceAction.SILENT, result.guidanceAction)
    }

    // ─── NavigationResult 일관성 ───

    @Test
    fun `NavigationResult의 모든 필드가 의미있는 값을 가진다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        val state = stateBefore(targetLon = 126.97840, offsetMeters = 20.0)

        val result = navigator.update(state, currentTimeMs = 1_000)

        // 검증: 모든 필드가 합리적 범위
        assertTrue(result.message.isNotEmpty(), "메시지 비어있음")
        assertTrue(result.distanceToWaypoint >= 0f, "거리가 음수: ${result.distanceToWaypoint}")
        assertTrue(
            result.headingDiff in -180f..180f,
            "headingDiff 범위 벗어남: ${result.headingDiff}"
        )
        assertTrue(
            result.currentWaypointIndex >= 0,
            "인덱스 음수: ${result.currentWaypointIndex}"
        )
    }

    // ─── 사전 안내 (annotation) 통합 ───

    @Test
    fun `annotations 미제공이면 annotationAnnouncement 는 항상 null`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        val state = stateBefore(targetLon = 126.97840, offsetMeters = 5.0)

        val result = navigator.update(state, currentTimeMs = 1_000)

        assertEquals(null, result.annotationAnnouncement)
    }

    @Test
    fun `안내 거리 안에 들어오면 annotationAnnouncement 가 발화된다`() {
        val waypoints = makeStraightRoute()
        // W1 (인덱스 0) 을 시작 annotation 으로 등록.
        // distanceFromStartM = 0 이고 트리거 거리(curve) = 15m 이내이면 발화.
        val ann = PathAnnotation(
            startWaypointIndex = 0,
            endWaypointIndex = 1,
            type = PathSegmentType.SLIGHT_CURVE,
            direction = TurnDirection.RIGHT,
            totalAngle = 20.0,
            peakAngle = 10.0,
            distanceFromStartM = 0.0,
            announceMessage = MessageBuilder.buildAnnotationAnnounce(
                PathAnnotation.defaults().copy(
                    type = PathSegmentType.SLIGHT_CURVE,
                    direction = TurnDirection.RIGHT,
                ),
            ),
        )
        val navigator = TrustBasedNavigator(
            waypoints = waypoints,
            annotations = listOf(ann),
        )
        // 사용자는 W1 5m 서쪽 — userCum ≈ -5m → gap = 0 - (-5) = 5m, 트리거 안.
        val state = stateBefore(targetLon = 126.97840, offsetMeters = 5.0)

        val result = navigator.update(state, currentTimeMs = 1_000)

        assertTrue(
            result.annotationAnnouncement?.isNotBlank() == true,
            "annotation 안내가 비어있다: ${result.annotationAnnouncement}",
        )
    }

    @Test
    fun `같은 annotation 은 두 번 발화되지 않는다`() {
        val ann = PathAnnotation(
            startWaypointIndex = 0,
            endWaypointIndex = 1,
            type = PathSegmentType.SLIGHT_CURVE,
            direction = TurnDirection.RIGHT,
            totalAngle = 20.0,
            peakAngle = 10.0,
            distanceFromStartM = 0.0,
            announceMessage = "테스트 안내",
        )
        val navigator = TrustBasedNavigator(
            waypoints = makeStraightRoute(),
            annotations = listOf(ann),
        )
        val state = stateBefore(targetLon = 126.97840, offsetMeters = 5.0)

        val first = navigator.update(state, currentTimeMs = 1_000)
        val second = navigator.update(state, currentTimeMs = 2_000)

        assertEquals("테스트 안내", first.annotationAnnouncement)
        assertEquals(null, second.annotationAnnouncement)
    }
}
