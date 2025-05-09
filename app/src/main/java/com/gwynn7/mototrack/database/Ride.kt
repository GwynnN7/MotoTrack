package com.gwynn7.mototrack.database

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize


@Parcelize
@Entity(tableName = "rides")
data class Ride(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    var totalDistance: Double = 0.0,
    var startTime: Long = 0,
    var endTme: Long = 0,
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
    val altitude: Double,
    val acceleration: Double,
    val speed: Int,
    val leanAngle: Int
): Parcelable
