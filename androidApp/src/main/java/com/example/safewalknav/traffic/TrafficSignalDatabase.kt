package com.example.safewalknav.traffic

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrafficSignalEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TrafficSignalDatabase : RoomDatabase() {
    abstract fun trafficSignalDao(): TrafficSignalDao

    companion object {
        @Volatile
        private var INSTANCE: TrafficSignalDatabase? = null

        fun getInstance(context: Context): TrafficSignalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrafficSignalDatabase::class.java,
                    "traffic_signal.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}