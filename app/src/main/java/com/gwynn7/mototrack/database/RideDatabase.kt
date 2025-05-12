package com.gwynn7.mototrack.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Ride::class, RideData::class], version = 4, exportSchema = false)
abstract class RideDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDAO

    companion object {
        @Volatile private var INSTANCE: RideDatabase? = null

        fun getInstance(context: Context): RideDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "mototrack_database"
                ).fallbackToDestructiveMigration(true)
                .build().also { INSTANCE = it }
            }
        }
    }
}