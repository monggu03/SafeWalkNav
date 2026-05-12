package com.example.safewalknav.traffic

import com.example.safewalknav.navigation.SeoulTrafficSignalLocationApiClient
import com.example.safewalknav.navigation.TrafficSignalLocation
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
        val xmlPages = apiClient.fetchTrafficSignalXmlPages()
        val entities = mutableListOf<TrafficSignalEntity>()

        for (xml in xmlPages) {
            val pageEntities = withContext(Dispatchers.Default) {
                TrafficSignalXmlParser.parse(xml)
            }

            if (pageEntities.isEmpty()) {
                break
            }

            entities.addAll(pageEntities)
        }

        return entities
    }

    private fun TrafficSignalEntity.toDomain(): TrafficSignalLocation {
        return TrafficSignalLocation(
            itstId = id,
            lat = lat,
            lon = lon
        )
    }
}