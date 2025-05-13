package com.gwynn7.mototrack.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

//Database functions

@Dao
interface RideDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: Ride): Long

    @Query("UPDATE rides SET totalDistance = :totalDistance WHERE id = :rideId")
    suspend fun updateRide(rideId: Long, totalDistance: Double): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRideData(data: List<RideData>)

    @Query("SELECT * FROM rides WHERE id = :rideId LIMIT 1")
    suspend fun getRideById(rideId: Long): Ride?

    @Query("SELECT * FROM rides")
    suspend fun getRides(): List<Ride>

    @Query("SELECT * FROM rides_data WHERE id = :id LIMIT 1")
    suspend fun getRideDataById(id: Long): RideData?

    @Query("SELECT * FROM rides_data WHERE rideId = :rideId")
    suspend fun getRideDataByRideId(rideId: Long): List<RideData>

    @Query("DELETE FROM rides WHERE id = :rideId")
    suspend fun deleteRide(rideId: Long)

    @Query("DELETE FROM rides_data WHERE rideId = :rideId")
    suspend fun deleteRideData(rideId: Long)
}