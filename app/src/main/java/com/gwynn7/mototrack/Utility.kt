package com.gwynn7.mototrack

import android.content.Context
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import java.util.Locale
import androidx.core.graphics.createBitmap
import kotlin.math.sqrt

//Utility class for sensors data

data class Vector3(val x: Float, val y: Float, val z: Float) {
    companion object{
        fun empty(): Vector3{
            return Vector3(0f, 0f, 0f)
        }
        fun fromArray(array: FloatArray): Vector3 {
            return Vector3(array[0], array[1], array[2])
        }
    }
    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun div(value: Float) = Vector3(x / value, y / value, z / value)
    operator fun times(value: Float) = Vector3(x * value, y * value, z * value)
    fun dot(other: Vector3) = x * other.x + y * other.y + z * other.z
    fun magnitude() = sqrt(x * x + y * y + z * z)
    fun project(axis: Vector3) = this - axis * dot(axis)
    fun normalize(): Vector3 {
        val vectorMagnitude = magnitude()
        return if (vectorMagnitude != 0f) Vector3(x / vectorMagnitude, y / vectorMagnitude, z / vectorMagnitude) else this
    }
}


//Function to get stats name tab

private val statsNames = arrayOf(
    R.string.lean_title,
    R.string.speed_title,
    R.string.acceleration_title,
    R.string.altitude_title
)

fun getStatsName(index: Int): Int
{
    return statsNames[index]
}

//Function to format total seconds to time string
fun formatTime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours.toInt(), minutes.toInt(), secs.toInt())
}

//Function to convert XML drawable to bitmap
fun bitmapDescriptorFromVector(context: Context, vectorResId: Int): BitmapDescriptor {
    val vectorDrawable = ContextCompat.getDrawable(context, vectorResId)!!
    vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
    val bitmap = createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
    val canvas = Canvas(bitmap)
    vectorDrawable.draw(canvas)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

//Pages of the PageViewer and Tabs
enum class Pages(val value: Int)
{
    Lean(0),
    Speed(1),
    Acceleration(2),
    Altitude(3);

    companion object{
        fun fromInt(value: Int): Pages? = Pages.entries.find { it.value == value }
    }
}

//Different states of tracking activity, used to reset the activity when it gets stopped or refreshed
enum class TrackActivityState {
    SearchingGps,
    Calibrating,
    Tracking
}