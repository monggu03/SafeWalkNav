package com.example.safewalknav.navigation

/**
 * 횡단보도 신호등 잔여시간 조회 API 계약.
 *
 * commonMain에는 인터페이스만 두고, 실제 구현(REST 호출 등)은
 * 기존 SignalApiClient 같은 클래스가 이 인터페이스를 구현하도록 한다.
 */
interface TrafficApi {
    /**
     * 특정 횡단보도의 현재 잔여 신호 시간(초)을 조회한다.
     *
     * @param crosswalkId 횡단보도 식별자
     * @return 잔여 초. 0 이상의 정수.
     */
    suspend fun fetchRemainingTime(crosswalkId: String): Int
}