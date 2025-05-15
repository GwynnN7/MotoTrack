package com.gwynn7.mototrack.database

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

//Database classes

@Parcelize
@Entity(tableName = "rides")
data class Ride(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    var totalDistance: Double = 0.0,
    var startTime: Long = 0,
    var totalSeconds: Long = 0,
    var snapshot: String? = null
): Parcelable

@Parcelize
@Entity(tableName = "rides_data")
data class RideData(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    var rideId: Long,
    val latitude: Double,
    val longitude: Double,
    var altitude: Double,
    var acceleration: Float,
    var speed: Int,
    var leanAngle: Int
): Parcelable
