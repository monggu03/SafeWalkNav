package com.example.safewalknav.traffic

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "traffic_signals")
data class TrafficSignalEntity(
    @PrimaryKey val id: String,
    val xcrd: Double,
    val ycrd: Double,
    val lat: Double,
    val lon: Double,
    val updatedAt: Long
)