package com.example.safewalknav.navigation.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 앱이 지금 보여줘야 할 화면 — **안드로이드·iOS 의 단일 진실원천(single source of truth)**.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 왜 shared 에 있나
 * ─────────────────────────────────────────────────────────────────────────────
 * 예전엔 화면 상태가 두 플랫폼에 따로 있었다.
 *   - Android: 명시적 `enum AppState` (IDLE/LISTENING/SEARCHING/RESULTS/NAVIGATING/ARRIVED)
 *     + `showState()` 단일 전환 함수 — 깔끔한 상태기계.
 *   - iOS: 상태가 세 군데로 흩어짐 (TabView 2탭 + 파생 NavTabState + 미사용 VoiceFlowStage).
 *     LISTENING·SEARCHING·ARRIVED 전용 상태가 없고, 도착 후 자동 복귀도 없음.
 * 결과적으로 두 앱의 화면 흐름이 갈라졌다.
 *
 * 이 모델을 commonMain 에 두고 [AppScreenStateMachine] 이 흐름을 관리하면, 두 플랫폼이
 * **같은 상태·같은 전환**을 따른다. 각 플랫폼은 이 상태를 받아 **네이티브로 렌더링**만 한다
 * (Android View, iOS SwiftUI) — 그래서 네이티브 접근성(TalkBack/VoiceOver)을 유지한다.
 *
 * "무엇을 보여줄지(상태·데이터)"는 shared 가, "어떻게 그릴지(픽셀·접근성)"는 플랫폼이 맡는다.
 * 이것이 화면을 Compose 로 재작성하지 않고도 UI 를 통일하는 방법이다.
 */
sealed class AppScreenState {

    /** 대기 — 목적지 입력 전. 화면 길게 누르기(또는 더블탭)로 음성 입력 시작. */
    data object Idle : AppScreenState()

    /** 음성 인식(STT) 진행 중. */
    data object Listening : AppScreenState()

    /** 목적지 검색(TMap API) 중. */
    data object Searching : AppScreenState()

    /** 목적지 후보 목록 (최대 5개). 사용자가 하나 선택하면 내비 시작. */
    data class Results(val destinations: List<ScreenPoi>) : AppScreenState()

    /**
     * 내비게이션 중. [mode] 로 평상 이동(나침반)과 횡단보도(카메라)를 세분한다.
     * AppState 만으로 화면이 안 정해지던 문제(Android 의 nav 내부 모드 전환)를 여기서 명시화.
     */
    data class Navigating(
        val mode: NavMode,
        val guidance: String,
        val distanceToDestinationMeters: Int?,
    ) : AppScreenState()

    /** 도착. [AppScreenStateMachine.ARRIVED_AUTO_RETURN_MS] 후 Idle 로 복귀한다(양 플랫폼 동일). */
    data class Arrived(val destinationName: String) : AppScreenState()
}

/** 내비게이션 중 하위 모드. */
sealed class NavMode {
    /** 평상 이동 — 방향 안내(나침반). [targetBearingDeg] = 가야 할 방위(도), 없으면 null. */
    data class Walking(val targetBearingDeg: Float?) : NavMode()

    /** 횡단보도 접근·대기 — 카메라로 신호등 인식. 색 판정은 SignalDecisionEngine 이 담당. */
    data object Crosswalk : NavMode()
}

/** 화면 표시용 POI (플랫폼 무관 투영). 거리(m)는 현재 위치를 알 때만. */
data class ScreenPoi(
    val name: String,
    val distanceMeters: Int?,
)

/**
 * 음성/검색 단계 — STT·검색은 플랫폼(네이티브)이 구동하므로, 그 진행을 이벤트로 알려준다.
 * (내비게이션 단계는 NavigationManager 플로우에서 옴 — [NavSnapshot])
 */
sealed class VoicePhase {
    data object Idle : VoicePhase()
    data object Listening : VoicePhase()
    data object Searching : VoicePhase()
    data class Results(val destinations: List<ScreenPoi>) : VoicePhase()
}

/**
 * 내비게이션 상태 스냅샷 — NavigationManager 의 StateFlow 값들을 플랫폼이 모아 밀어넣는다.
 * (이미 commonMain 에 있는 값들이라 두 플랫폼이 같은 원천을 본다)
 */
data class NavSnapshot(
    val isNavigating: Boolean = false,
    val isArrived: Boolean = false,             // arrivalState == ARRIVED
    val inCrosswalkZone: Boolean = false,
    val hasNearbyTrafficSignal: Boolean = false,
    val guidance: String = "",
    val distanceToDestinationMeters: Int? = null,
    val targetBearingDeg: Float? = null,
    val destinationName: String = "",
) {
    companion object {
        val EMPTY = NavSnapshot()
    }
}

/**
 * 화면 상태 기계 — **두 플랫폼이 공유하는 단일 상태 소스**.
 *
 * 입력 둘을 합쳐 [AppScreenState] 하나를 만든다:
 *   1) 음성/검색 단계 [VoicePhase] — 플랫폼이 STT/검색 진행에 맞춰 갱신 ([setVoicePhase] 등)
 *   2) 내비게이션 스냅샷 [NavSnapshot] — NavigationManager 플로우를 플랫폼이 밀어넣음 ([updateNavigation])
 *
 * 우선순위: **내비게이션이 음성 단계를 압도한다.** 내비 중이면 음성 단계와 무관하게 내비 화면.
 * (Android 의 IDLE→…→NAVIGATING→ARRIVED→IDLE 흐름과 일치)
 *
 * 이 클래스는 **부수효과가 없다.** 타이머·TTS·렌더링 없음. 순수하게 상태만 계산한다.
 * (도착 후 자동 복귀 타이밍은 플랫폼이 [ARRIVED_AUTO_RETURN_MS] 를 써서 [onNavigationStopped] 호출)
 */
class AppScreenStateMachine {

    private var voice: VoicePhase = VoicePhase.Idle
    private var nav: NavSnapshot = NavSnapshot.EMPTY

    private val _state = MutableStateFlow<AppScreenState>(AppScreenState.Idle)

    /** 두 플랫폼이 collect 해서 렌더링하는 단일 화면 상태. */
    val state: StateFlow<AppScreenState> = _state.asStateFlow()

    // ── 음성/검색 단계 (플랫폼 → 기계) ──
    fun setVoicePhase(phase: VoicePhase) { voice = phase; recompute() }
    fun onIdle() = setVoicePhase(VoicePhase.Idle)
    fun onListening() = setVoicePhase(VoicePhase.Listening)
    fun onSearching() = setVoicePhase(VoicePhase.Searching)
    fun onResults(destinations: List<ScreenPoi>) = setVoicePhase(VoicePhase.Results(destinations))

    // ── 내비게이션 스냅샷 (플랫폼 → 기계) ──
    fun updateNavigation(snapshot: NavSnapshot) { nav = snapshot; recompute() }

    /**
     * 내비게이션 종료 시 호출. 음성 단계를 Idle 로 되돌려 RESULTS 로 다시 빠지지 않게 한다.
     * (내비 스냅샷도 비운다)
     */
    fun onNavigationStopped() {
        voice = VoicePhase.Idle
        nav = NavSnapshot.EMPTY
        recompute()
    }

    private fun recompute() {
        _state.value = reduce(voice, nav)
    }

    companion object {
        /** 도착 화면을 보여준 뒤 Idle 로 자동 복귀하기까지의 시간. 양 플랫폼 동일하게 사용. */
        const val ARRIVED_AUTO_RETURN_MS = 3_000L

        /**
         * 순수 축약 함수 — (음성 단계, 내비 스냅샷) → 화면 상태.
         * 테스트가 이 함수 하나만 검증하면 전이 규칙 전체가 커버된다.
         */
        fun reduce(voice: VoicePhase, nav: NavSnapshot): AppScreenState {
            // 내비게이션이 최우선. 내비 중이면 음성 단계와 무관하게 내비 화면.
            if (nav.isNavigating) {
                return when {
                    nav.isArrived ->
                        AppScreenState.Arrived(nav.destinationName)
                    nav.inCrosswalkZone && nav.hasNearbyTrafficSignal ->
                        AppScreenState.Navigating(
                            mode = NavMode.Crosswalk,
                            guidance = nav.guidance,
                            distanceToDestinationMeters = nav.distanceToDestinationMeters,
                        )
                    else ->
                        AppScreenState.Navigating(
                            mode = NavMode.Walking(nav.targetBearingDeg),
                            guidance = nav.guidance,
                            distanceToDestinationMeters = nav.distanceToDestinationMeters,
                        )
                }
            }
            // 내비 아님 → 음성/검색 단계에 따라.
            return when (voice) {
                VoicePhase.Idle -> AppScreenState.Idle
                VoicePhase.Listening -> AppScreenState.Listening
                VoicePhase.Searching -> AppScreenState.Searching
                is VoicePhase.Results -> AppScreenState.Results(voice.destinations)
            }
        }
    }
}
