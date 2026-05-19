package com.example.safewalknav.navigation.walking

/**
 * 보행 분석 및 내비게이션 알고리즘에서 사용하는 공통 임계값 상수들
 */
object NavigationConstants {
    // --- 보행 쏠림(Lean) 판단 기준 ---
    const val LEAN_THRESHOLD = 25.0       // 쏠림으로 판단할 최소 각도 (degree)[cite: 1]
    const val SHAKE_THRESHOLD = 13.0f     // 흔들기 감지 임계값 (m/s^2)[cite: 1]

    // --- 보행 진단(Diagnostic) 관련 ---[cite: 1]
    const val STABLE_WALKING_TIME = 2000L // 안정된 보행으로 판단하는 최소 시간 (ms)[cite: 1]
    const val MAX_DIAGNOSTIC_HISTORY = 50 // 보행 분석용 데이터 저장 개수[cite: 1]

    // --- 위치 및 거리 관련 기준 ---[cite: 3]
    const val GPS_ACCURACY_LIMIT = 25.0f  // 신뢰할 수 있는 GPS 오차 한계 (m)[cite: 3]
    const val APPROACHING_DIST = 15.0f    // '목적지 근처' 비콘 시작 거리 (m)[cite: 3]
    const val NEAR_DIST = 5.0f            // '거의 도착' 방향 비콘 전환 거리 (m)[cite: 3]
    const val ARRIVAL_DIST = 2.0f         // '최종 도착' 판정 거리 (m)[cite: 3]

    // --- 비콘 및 소리 주기 ---[cite: 1, 3]
    const val BEACON_INTERVAL_FAR = 3000L // 10m 이상 거리에서의 비프 간격[cite: 3]
    const val BEACON_INTERVAL_NEAR = 400L // 3m 이내 거리에서의 비프 간격[cite: 3]
}