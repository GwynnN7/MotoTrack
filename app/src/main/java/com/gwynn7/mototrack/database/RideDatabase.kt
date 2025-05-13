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

        //Create database instance on first function call, return the instance on next calls
        fun getInstance(context: Context): RideDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "mototrack_database"
                ).fallbackToDestructiveMigration(true) //Clear database on migration
                .build().also { INSTANCE = it } //Set the instance
            }
        }
    }
}