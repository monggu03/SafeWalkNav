package com.example.safewalknav.traffic

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrafficSignalDao {
    @Query("SELECT * FROM traffic_signals")
    suspend fun getAll(): List<TrafficSignalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(signals: List<TrafficSignalEntity>)

    @Query("DELETE FROM traffic_signals")
    suspend fun clearAll()

    @Query("SELECT MAX(updatedAt) FROM traffic_signals")
    suspend fun getLastUpdatedAt(): Long?
}