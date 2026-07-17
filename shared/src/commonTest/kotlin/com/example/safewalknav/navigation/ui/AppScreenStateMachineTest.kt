package com.example.safewalknav.navigation.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AppScreenStateMachine 전이 규칙 테스트.
 *
 * 두 플랫폼이 이 하나를 공유하므로, 여기서 전이가 맞으면 Android·iOS 화면 흐름이 일치한다.
 * 순수 reduce 함수 + 기계 상태 둘 다 검증한다.
 */
class AppScreenStateMachineTest {

    private fun poi(n: String, d: Int?) = ScreenPoi(n, d)
    private val navOff = NavSnapshot.EMPTY
    private fun navOn(
        arrived: Boolean = false,
        zone: Boolean = false,
        signal: Boolean = false,
        guidance: String = "직진",
        dist: Int? = 50,
        bearing: Float? = 90f,
        dest: String = "도서관",
    ) = NavSnapshot(
        isNavigating = true, isArrived = arrived, inCrosswalkZone = zone,
        hasNearbyTrafficSignal = signal, guidance = guidance,
        distanceToDestinationMeters = dist, targetBearingDeg = bearing, destinationName = dest,
    )

    // ── 음성/검색 단계 (내비 아님) ──

    @Test fun 기본은_Idle() {
        assertEquals(AppScreenState.Idle, AppScreenStateMachine.reduce(VoicePhase.Idle, navOff))
    }

    @Test fun 음성단계가_그대로_화면이_된다() {
        assertEquals(AppScreenState.Listening, AppScreenStateMachine.reduce(VoicePhase.Listening, navOff))
        assertEquals(AppScreenState.Searching, AppScreenStateMachine.reduce(VoicePhase.Searching, navOff))
    }

    @Test fun 검색결과는_Results로_투영된다() {
        val pois = listOf(poi("도서관", 120), poi("학관", 300))
        val s = AppScreenStateMachine.reduce(VoicePhase.Results(pois), navOff)
        assertIs<AppScreenState.Results>(s)
        assertEquals(2, s.destinations.size)
        assertEquals("도서관", s.destinations[0].name)
    }

    // ── 내비게이션이 음성 단계를 압도 ──

    @Test fun 내비중이면_음성단계와_무관하게_내비화면() {
        // 음성 단계가 Results 여도 내비 중이면 Navigating
        val s = AppScreenStateMachine.reduce(VoicePhase.Results(listOf(poi("x", 1))), navOn())
        assertIs<AppScreenState.Navigating>(s)
    }

    // ── 내비 하위 모드 ──

    @Test fun 평상이동은_Walking모드() {
        val s = AppScreenStateMachine.reduce(VoicePhase.Idle, navOn(zone = false, signal = false, bearing = 45f))
        assertIs<AppScreenState.Navigating>(s)
        assertIs<NavMode.Walking>(s.mode)
        assertEquals(45f, (s.mode as NavMode.Walking).targetBearingDeg)
    }

    @Test fun 횡단보도_zone과_신호등_모두일때만_Crosswalk모드() {
        // zone 만: 아직 Walking
        val onlyZone = AppScreenStateMachine.reduce(VoicePhase.Idle, navOn(zone = true, signal = false))
        assertIs<NavMode.Walking>((onlyZone as AppScreenState.Navigating).mode)
        // zone + 신호등: Crosswalk
        val both = AppScreenStateMachine.reduce(VoicePhase.Idle, navOn(zone = true, signal = true))
        assertEquals(NavMode.Crosswalk, (both as AppScreenState.Navigating).mode)
    }

    // ── 도착 ──

    @Test fun 도착은_Arrived로_목적지명_포함() {
        val s = AppScreenStateMachine.reduce(VoicePhase.Idle, navOn(arrived = true, dest = "중앙도서관"))
        assertIs<AppScreenState.Arrived>(s)
        assertEquals("중앙도서관", s.destinationName)
    }

    // ── 기계 (StateFlow) 동작 ──

    @Test fun 기계는_이벤트마다_state를_갱신한다() {
        val m = AppScreenStateMachine()
        assertEquals(AppScreenState.Idle, m.state.value)

        m.onListening()
        assertEquals(AppScreenState.Listening, m.state.value)

        m.onSearching()
        assertEquals(AppScreenState.Searching, m.state.value)

        m.onResults(listOf(poi("도서관", 100)))
        assertIs<AppScreenState.Results>(m.state.value)

        // 내비 시작 → 음성 단계와 무관하게 Navigating
        m.updateNavigation(navOn(guidance = "왼쪽으로"))
        val nav = m.state.value
        assertIs<AppScreenState.Navigating>(nav)
        assertEquals("왼쪽으로", nav.guidance)

        // 횡단보도 진입
        m.updateNavigation(navOn(zone = true, signal = true))
        assertEquals(NavMode.Crosswalk, (m.state.value as AppScreenState.Navigating).mode)

        // 도착
        m.updateNavigation(navOn(arrived = true, dest = "도서관"))
        assertIs<AppScreenState.Arrived>(m.state.value)
    }

    @Test fun 내비종료하면_Idle로_복귀하고_Results로_안빠진다() {
        val m = AppScreenStateMachine()
        m.onResults(listOf(poi("도서관", 100)))   // 음성 단계는 Results 로 남아있음
        m.updateNavigation(navOn())
        assertIs<AppScreenState.Navigating>(m.state.value)

        // 종료 → Idle (음성 Results 잔재로 되빠지면 안 됨)
        m.onNavigationStopped()
        assertEquals(AppScreenState.Idle, m.state.value)
    }

    @Test fun ARRIVED_자동복귀_상수가_양플랫폼_공유값이다() {
        assertTrue(AppScreenStateMachine.ARRIVED_AUTO_RETURN_MS > 0)
        assertEquals(3_000L, AppScreenStateMachine.ARRIVED_AUTO_RETURN_MS)
    }
}
