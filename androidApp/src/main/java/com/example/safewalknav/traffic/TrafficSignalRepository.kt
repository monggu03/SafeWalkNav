package com.example.safewalknav.traffic

import com.example.safewalknav.navigation.TrafficSignalLocation
import android.util.Log

class TrafficSignalRepository(
    private val dao: TrafficSignalDao,
    private val apiClient: TrafficSignalLocationApiClient
) {
    suspend fun getTrafficSignals(): List<TrafficSignalLocation> {
        val local = dao.getAll()

        if (local.isNotEmpty()) {
            return local.map { it.toDomain() }
        }

        val remote = apiClient.fetchTrafficSignals()

        if (remote.isNotEmpty()) {
            dao.clearAll()
            dao.insertAll(remote)
        }
        Log.d(
            "TrafficSignalAPI",
            "remote size: ${remote.size}"
        )
        return dao.getAll().map { it.toDomain() }
    }

    private fun TrafficSignalEntity.toDomain(): TrafficSignalLocation {
        return TrafficSignalLocation(
            itstId = id,
            lat = lat,
            lon = lon
        )
    }
}