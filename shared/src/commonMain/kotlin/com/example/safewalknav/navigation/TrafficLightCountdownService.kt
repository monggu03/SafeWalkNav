package com.example.safewalknav.navigation

import kotlinx.datetime.Clock

/**
 * 횡단보도 신호등 잔여시간 카운트다운 서비스.
 *
 * 동작 원리:
 * - 마지막 API 호출 시점을 기억해두고, 쿨다운 시간(기본 60초) 안에 다시 호출되면
 *   캐싱된 값에서 경과 초만큼 빼서 반환한다.
 * - 쿨다운이 지나면 실제 API를 다시 호출해서 최신 값으로 갱신한다.
 *
 * 안드로이드 팀과 쿨다운 값 통일: 60초.
 */
class TrafficLightCountdownService(
    private val api: TrafficApi,
    private val cooldownMillis: Long = 60_000L
) {
    private var lastFetchTime: Long = 0
    private var cachedRemainingSeconds: Int = 0

    suspend fun getRemainingSeconds(crosswalkId: String): Int {
        val now = Clock.System.now().toEpochMilliseconds()
        val elapsed = now - lastFetchTime

        // 쿨다운 중이면 캐시 값에서 경과 시간만큼 빼서 반환
        if (elapsed < cooldownMillis) {
            return maxOf(0, cachedRemainingSeconds - (elapsed / 1000).toInt())
        }

        // 쿨다운 끝 -> API 재호출
        val fresh = api.fetchRemainingTime(crosswalkId)
        lastFetchTime = now
        cachedRemainingSeconds = fresh
        return fresh
    }
}