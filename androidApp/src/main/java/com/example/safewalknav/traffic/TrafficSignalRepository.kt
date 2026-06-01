package com.example.safewalknav.traffic

import com.example.safewalknav.navigation.signal.SeoulTrafficSignalLocationApiClient
import com.example.safewalknav.navigation.signal.TrafficSignalLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TrafficSignalRepository(
    private val dao: TrafficSignalDao,
    private val apiClient: SeoulTrafficSignalLocationApiClient
) {
    suspend fun getTrafficSignals(): List<TrafficSignalLocation> {
        val local = dao.getAll()

        if (local.isNotEmpty()) {
            return local.map { it.toDomain() }
        }

        val remote = fetchRemoteEntities()

        if (remote.isNotEmpty()) {
            dao.clearAll()
            dao.insertAll(remote)
        }

        return dao.getAll().map { it.toDomain() }
    }

    private suspend fun fetchRemoteEntities(): List<TrafficSignalEntity> {
        // 2026-06-02 OOM 대응 — 페이지 단위 즉시 Room insert.
        // 이전: 모든 페이지(60+개) XML 을 메모리에 누적 + 파싱 결과 entities 도 누적 → OOM
        // 변경: 페이지 받자마자 파싱 → Room 에 insert → entities/xml 메모리 해제
        val xmlPages = apiClient.fetchTrafficSignalXmlPages()
        if (xmlPages.isEmpty()) return emptyList()

        // 첫 페이지가 유효할 때만 기존 캐시 비움 (네트워크 실패 시 기존 캐시 보존).
        var hasClearedCache = false
        var totalInserted = 0

        for (xml in xmlPages) {
            val pageEntities = withContext(Dispatchers.Default) {
                TrafficSignalXmlParser.parse(xml)
            }

            if (pageEntities.isEmpty()) break

            if (!hasClearedCache) {
                dao.clearAll()
                hasClearedCache = true
            }
            // 페이지마다 즉시 Room 에 저장 → 메모리 누적 방지.
            dao.insertAll(pageEntities)
            totalInserted += pageEntities.size
        }

        return if (totalInserted > 0) dao.getAll() else emptyList()
    }

    private fun TrafficSignalEntity.toDomain(): TrafficSignalLocation {
        return TrafficSignalLocation(
            itstId = id,
            lat = lat,
            lon = lon
        )
    }
}