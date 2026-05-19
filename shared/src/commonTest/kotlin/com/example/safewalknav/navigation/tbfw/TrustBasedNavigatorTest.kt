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
 *   - 경도 0.00015 차이 ≈ 13m (HIGH 통과 거리 8m + 여유)
 *   - 너무 가까우면 W1 통과 시점에 W2까지 거리도 통과 거리 안에 들어가버림
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

        val result = navigator.update(state)

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

        val result = navigator.update(state)

        assertTrue(result.isFinished)
        assertEquals(MessageBuilder.MSG_ARRIVED_DESTINATION, result.message)
    }

    // ─── 정상 보행 시나리오 ───

    @Test
    fun `정상 GPS와 정방향 보행이면 HIGH 신뢰도가 나온다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        // W1 약 20m 서쪽에서 동쪽으로 보행
        val state = stateBefore(targetLon = 126.97840, offsetMeters = 20.0)

        val result = navigator.update(state)

        assertEquals(TrustLevel.HIGH, result.trustLevel)
        assertTrue(result.trustScore >= 80, "점수: ${result.trustScore}")
    }

    @Test
    fun `정상 보행 중에는 안전 경고가 안 뜬다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        val state = stateBefore(targetLon = 126.97840, offsetMeters = 20.0)

        val result = navigator.update(state)

        assertFalse(result.message.contains("정확도가"), "안전 경고 메시지: ${result.message}")
    }

    // ─── Waypoint 통과 시나리오 ───

    @Test
    fun `W1 근처에 도달하면 통과 처리되고 인덱스가 1이 된다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        // W1 약 3m 서쪽 (8m 통과 거리 안)
        val state = stateBefore(targetLon = 126.97840, offsetMeters = 3.0)

        val result = navigator.update(state)

        assertTrue(
            result.didPassWaypoint,
            "통과 처리 안 됨. 거리: ${result.distanceToWaypoint}, " +
            "trustLevel: ${result.trustLevel}, headingDiff: ${result.headingDiff}"
        )
        assertEquals(1, result.currentWaypointIndex)
    }

    @Test
    fun `통과 후 다음 update에서는 W2를 목표로 한다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())

        // 1차 update — W1 통과 (W1 약 3m 서쪽)
        val first = navigator.update(stateBefore(targetLon = 126.97840, offsetMeters = 3.0))
        assertTrue(first.didPassWaypoint, "1차에서 W1 통과 실패")

        // 2차 update — W1과 W2 사이 (W2 약 7m 서쪽)
        val result = navigator.update(stateBefore(targetLon = 126.97855, offsetMeters = 10.0))

        assertEquals(1, result.currentWaypointIndex, "여전히 W2(인덱스 1)가 목표여야 함")
        assertFalse(result.isFinished)
    }

    // ─── GPS 튐 시나리오 (Forward-Only 핵심) ───

    @Test
    fun `W1 통과 후 GPS가 W1 뒤로 튀어도 인덱스는 감소하지 않는다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())

        // 1단계: W1 통과
        val pass = navigator.update(stateBefore(targetLon = 126.97840, offsetMeters = 3.0))
        assertTrue(pass.didPassWaypoint, "1단계에서 W1 통과 실패")
        assertEquals(1, pass.currentWaypointIndex)

        // 2단계: GPS가 갑자기 W1보다 훨씬 서쪽으로 튐 (accuracy도 나빠짐)
        val jumped = navigator.update(UserState(
            location = goodGps(
                lat = 37.5666,
                lon = 126.97820,           // W1보다 약 18m 서쪽
                accuracy = 35f             // 부정확
            ),
            heading = 90f
        ))

        // Forward-Only: 인덱스 1 유지, 절대 0으로 안 돌아감
        assertEquals(1, jumped.currentWaypointIndex, "Forward-Only가 깨졌음")
    }

    // ─── Trust LOW 시나리오 ───

    @Test
    fun `GPS accuracy가 매우 나쁘면 Trust가 LOW 또는 CRITICAL이다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        val state = UserState(
            location = goodGps(
                lat = 37.5666, lon = 126.97835,
                accuracy = 60f    // 매우 부정확
            ),
            heading = 90f
        )

        val result = navigator.update(state)

        assertTrue(
            result.trustLevel == TrustLevel.LOW || result.trustLevel == TrustLevel.CRITICAL,
            "Trust Level: ${result.trustLevel}, score: ${result.trustScore}"
        )
    }

    @Test
    fun `Trust LOW면 W1 코앞이어도 통과 처리 안 된다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        // W1 3m 서쪽 + 정방향이지만 GPS accuracy가 나쁨
        val state = UserState(
            location = goodGps(
                lat = 37.5666,
                lon = 126.97840 - 3.0 * 0.00001137,
                accuracy = 60f                     // 부정확 → LOW
            ),
            heading = 90f                          // 정방향
        )

        val result = navigator.update(state)

        // Trust LOW/CRITICAL이면 통과 처리 안 함 (Forward-Only 동결)
        if (result.trustLevel == TrustLevel.LOW ||
            result.trustLevel == TrustLevel.CRITICAL) {
            assertFalse(result.didPassWaypoint, "Trust 낮은데 통과 처리됨")
            assertEquals(0, result.currentWaypointIndex)
        }
    }

    @Test
    fun `Trust LOW면 거리 안내 대신 보수적 안내가 나온다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        val state = UserState(
            location = goodGps(
                lat = 37.5666, lon = 126.9783,
                speed = 0.05f,           // 거의 정지
                accuracy = 40f           // 부정확
            ),
            heading = 180f               // 잘못된 방향 (남쪽)
        )

        val result = navigator.update(state)

        // Trust LOW/CRITICAL이면 일반 거리 안내가 아닌 안전 메시지
        if (result.trustLevel == TrustLevel.LOW) {
            assertEquals(MessageBuilder.MSG_TRUST_LOW, result.message)
        } else if (result.trustLevel == TrustLevel.CRITICAL) {
            assertEquals(MessageBuilder.MSG_TRUST_CRITICAL, result.message)
        }
    }

    // ─── 종료 시나리오 ───

    @Test
    fun `모든 waypoint 통과하면 도착 메시지가 나온다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())

        // W1 통과 (W1 3m 서쪽)
        val r1 = navigator.update(stateBefore(targetLon = 126.97840, offsetMeters = 3.0))
        assertTrue(r1.didPassWaypoint, "W1 통과 실패")

        // W2 통과 (W2 3m 서쪽)
        val r2 = navigator.update(stateBefore(targetLon = 126.97855, offsetMeters = 3.0))
        assertTrue(r2.didPassWaypoint, "W2 통과 실패")

        // W3 통과 (W3 3m 서쪽)
        val r3 = navigator.update(stateBefore(targetLon = 126.97870, offsetMeters = 3.0))

        assertTrue(
            r3.isFinished || r3.didPassWaypoint,
            "W3 통과 또는 종료 상태가 아님. 인덱스: ${r3.currentWaypointIndex}"
        )
    }

    @Test
    fun `종료 후 update를 더 호출해도 종료 상태를 유지한다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())

        // 3개 다 통과
        navigator.update(stateBefore(targetLon = 126.97840, offsetMeters = 3.0))
        navigator.update(stateBefore(targetLon = 126.97855, offsetMeters = 3.0))
        navigator.update(stateBefore(targetLon = 126.97870, offsetMeters = 3.0))

        // 그 후 추가 호출 (전혀 다른 위치)
        val result = navigator.update(UserState(
            location = goodGps(37.5666, 126.9790),
            heading = 90f
        ))

        assertTrue(result.isFinished)
        assertEquals(MessageBuilder.MSG_ARRIVED_DESTINATION, result.message)
    }

    // ─── hasAccuracy=false 처리 ───

    @Test
    fun `hasAccuracy=false면 보수적으로 처리되어 통과가 어려워진다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())

        // accuracy 정보 없는 상태로 W1 근처
        val noAccuracyState = UserState(
            location = GpsLocation(
                latitude = 37.5666,
                longitude = 126.97840 - 3.0 * 0.00001137,  // W1 3m 서쪽
                speed = 1.2f,
                bearing = 90f,
                accuracy = 0f,           // 0이지만 hasAccuracy=false
                hasAccuracy = false      // ★
            ),
            heading = 90f
        )

        val result = navigator.update(noAccuracyState)

        // accuracy=0이어도 hasAccuracy=false면 50f로 처리되어 점수가 낮아짐
        // → Trust가 HIGH로 안 나와야 함
        assertTrue(
            result.trustLevel != TrustLevel.HIGH,
            "hasAccuracy=false인데 HIGH로 분류됨. 점수: ${result.trustScore}"
        )
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

        val result = navigator.update(state)

        // GPS bearing이 정방향이므로 점수가 정상 나와야 함
        assertTrue(
            result.trustScore >= 60,
            "heading null인데 점수 낮음. 점수: ${result.trustScore}"
        )
    }

    // ─── NavigationResult 일관성 ───

    @Test
    fun `NavigationResult의 모든 필드가 의미있는 값을 가진다`() {
        val navigator = TrustBasedNavigator(makeStraightRoute())
        val state = stateBefore(targetLon = 126.97840, offsetMeters = 20.0)

        val result = navigator.update(state)

        // 검증: 모든 필드가 합리적 범위
        assertTrue(result.message.isNotEmpty(), "메시지 비어있음")
        assertTrue(result.trustScore in 0..100, "점수 범위 벗어남: ${result.trustScore}")
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

        val result = navigator.update(state)

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

        val result = navigator.update(state)

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

        val first = navigator.update(state)
        val second = navigator.update(state)

        assertEquals("테스트 안내", first.annotationAnnouncement)
        assertEquals(null, second.annotationAnnouncement)
    }
}
