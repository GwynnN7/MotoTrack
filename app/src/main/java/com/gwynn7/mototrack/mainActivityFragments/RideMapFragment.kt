package com.gwynn7.mototrack.mainActivityFragments

import android.graphics.Color
import android.location.Location
import androidx.fragment.app.Fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.gwynn7.mototrack.R
import com.gwynn7.mototrack.database.Ride
import com.gwynn7.mototrack.database.RideData
import com.gwynn7.mototrack.databinding.FragmentRideMapBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

class RideMapFragment(private val pageId: Int, private val ride: Ride, private val rideData: List<RideData>): Fragment(), OnMapReadyCallback {
    private var _binding: FragmentRideMapBinding? = null
    private val binding get() = _binding!!

    private lateinit var mMap: GoogleMap
    private var lastMarker: Marker? = null

    private var pageType: Pages = Pages.Lean
    private lateinit var downSampledData: List<RideData>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pageType = Pages.fromInt(pageId) ?: Pages.Lean
        downSampledData = downSampleData(rideData)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentRideMapBinding.inflate(inflater, container, false)
        val root = binding.root

        val mapFragment = childFragmentManager.findFragmentById(R.id.frag_map) as SupportMapFragment
        mapFragment.getMapAsync(this@RideMapFragment)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onMapReady(map: GoogleMap) {
        mMap = map
        mMap.mapType = GoogleMap.MAP_TYPE_HYBRID
        mMap.isIndoorEnabled = false

        if(downSampledData.isEmpty()) return

        val start = LatLng(downSampledData.first().latitude, downSampledData.first().longitude)
        val end = LatLng(downSampledData.last().latitude, downSampledData.last().longitude)

        val maxIndex = when(pageType)
        {
            Pages.Lean -> downSampledData.withIndex().maxByOrNull { it.value.leanAngle }
            Pages.Speed -> downSampledData.withIndex().maxByOrNull { it.value.speed }
            Pages.Acceleration -> downSampledData.withIndex().maxByOrNull { it.value.acceleration }
            Pages.Altitude -> downSampledData.withIndex().maxByOrNull { it.value.altitude }
        }?.index ?: 0

        var maxValuePosition = LatLng(downSampledData[maxIndex].latitude, downSampledData[maxIndex].longitude)
        if(maxIndex > 0)
        {
            val newLat = downSampledData[maxIndex - 1].latitude + (maxValuePosition.latitude - downSampledData[maxIndex - 1].latitude) / 2
            val newLong = downSampledData[maxIndex - 1].longitude + (maxValuePosition.longitude - downSampledData[maxIndex - 1].longitude) / 2
            maxValuePosition = LatLng(newLat, newLong)
        }
        mMap.addMarker(
            MarkerOptions()
                .position(maxValuePosition)
                .title(getMaxStatName())
                .snippet(getStatValue(maxIndex))
        )

        mMap.addMarker(
            MarkerOptions()
                .position(start)
                .title("Start")
        )
        mMap.addMarker(
            MarkerOptions()
                .position(end)
                .title("End")
        )

        val builder = LatLngBounds.Builder()
        downSampledData.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
        val bounds = builder.build()
        val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 100)
        mMap.moveCamera(cameraUpdate)

        mMap.setOnMapLoadedCallback {
            binding.loadingMapLayout.visibility = View.GONE
            drawRidePath()
        }
    }

    private fun drawRidePath() {
        if(downSampledData.size < 2) return

        lifecycleScope.launch {
            val polylineData = withContext(Dispatchers.Default) {
                val lines = mutableListOf<PolylineOptions>()

                for (i in 1 until downSampledData.size) {
                    val p1 = LatLng(downSampledData[i-1].latitude, downSampledData[i-1].longitude)
                    val p2 = LatLng(downSampledData[i].latitude, downSampledData[i].longitude)

                    val value = when(pageType)
                    {
                        Pages.Lean -> downSampledData[i].leanAngle
                        Pages.Speed -> downSampledData[i].speed
                        Pages.Acceleration -> downSampledData[i].acceleration.roundToInt()
                        Pages.Altitude -> downSampledData[i].altitude.roundToInt()
                    }
                    val color = getColorFromRange(value)
                    lines.add(
                        PolylineOptions()
                            .clickable(true)
                            .geodesic(true)
                            .add(p1, p2)
                            .color(color)
                            .width(8f)
                    )
                }
                lines
            }

            lifecycleScope.launch {
                val numPointsPerSec = max(polylineData.size / 4000, 1)
                var index = 0
                while(index != polylineData.size){
                    for(i in 0 until numPointsPerSec)
                    {
                        val line = mMap.addPolyline(polylineData[index])
                        line.tag = index
                        index++
                    }
                    delay(2)
                }
            }

            mMap.setOnPolylineClickListener {
                val index = it.tag as Int

                val firstPoint = it.points.first()
                val lastPoint = it.points.last()
                val latitude = firstPoint.latitude + (lastPoint.latitude - firstPoint.latitude) / 2
                val longitude = firstPoint.longitude + (lastPoint.longitude - firstPoint.longitude) / 2
                val pointPosition = LatLng(latitude, longitude)

                val marker = mMap.addMarker(
                    MarkerOptions()
                        .position(pointPosition)
                        .title(getStatValue(index+1))
                )
                lastMarker?.remove()
                marker?.showInfoWindow()
                lastMarker = marker
            }
        }
    }

    fun downSampleData(data: List<RideData>): List<RideData> {
        val newRideData: MutableList<RideData> = mutableListOf(data.first())
        var index = 0
        while(index < data.size - 1)
        {
            val p0 = data[index]
            val p0Loc = Location("").apply {
                latitude = p0.latitude
                longitude = p0.longitude
            }

            var minBearing = 360f
            var maxSpeed: Int = 0
            var maxLean: Int = 0
            var maxAcceleration: Double = 0.0
            var maxAltitude: Double = 0.0

            for(i in 1..10)
            {
                val px = data[index+i]
                val pxLoc = Location("").apply {
                    latitude = px.latitude
                    longitude = px.longitude
                }

                val bearing = p0Loc.bearingTo(pxLoc)
                minBearing = min(minBearing, bearing)

                maxSpeed = max(maxSpeed, px.speed)
                maxLean = max(maxLean, px.leanAngle)
                maxAcceleration = max(maxAcceleration, px.acceleration)
                maxAltitude = max(maxAltitude, px.altitude)

                if(
                    (abs(p0Loc.bearingTo(pxLoc) - minBearing) > 5) ||
                    (abs(px.leanAngle - p0.leanAngle) >= 5) ||
                    (px.leanAngle >= 20) ||
                    (abs(px.acceleration - p0.acceleration) >= 4) ||
                    (abs(px.speed - p0.speed) >= 20) ||
                    (p0Loc.distanceTo(pxLoc) > 500) ||
                    (i == 10 || index+i == data.size-1)
                    )
                {
                    newRideData.add(px.copy(altitude = maxAltitude, acceleration = maxAcceleration, speed = maxSpeed, leanAngle = maxLean))
                    index += i
                    break
                }
            }
        }
        return newRideData
    }

    private fun getMaxStatName(): String
    {
        val name = when(pageType)
        {
            Pages.Lean -> statsNames[Pages.Lean.value]
            Pages.Speed -> statsNames[Pages.Speed.value]
            Pages.Acceleration -> statsNames[Pages.Acceleration.value]
            Pages.Altitude -> statsNames[Pages.Altitude.value]
        }
        return "Max ${resources.getString(name)}"
    }

    private fun getStatValue(index: Int): String
    {
        return when(pageType)
        {
            Pages.Lean -> "%d°".format(downSampledData[index].leanAngle)
            Pages.Speed -> "%d km/h".format(downSampledData[index].speed)
            Pages.Acceleration -> "%.2f m/s²".format(downSampledData[index].acceleration)
            Pages.Altitude -> "%d m".format(downSampledData[index].altitude.roundToInt())
        }
    }

    val colors = arrayOf(Color.CYAN, Color.GREEN, Color.YELLOW, "#FFA500".toColorInt(), Color.RED, Color.MAGENTA)
    val ranges = mapOf<Pages, Array<Int>>(
        Pages.Lean to arrayOf(10, 20, 30, 40, 50),
        Pages.Speed to arrayOf(40, 80, 120, 160, 200),
        Pages.Acceleration to arrayOf(2, 4, 6, 8, 10),
        Pages.Altitude to arrayOf(100, 400, 800, 1200, 1600)
    )

    private fun getColorFromRange(value: Int): Int {
        for(i in 0..(colors.size-2))
        {
            if(abs(value) < (ranges[pageType]?.get(i) ?: 0)) return colors[i]
        }
        return colors.last()
    }
}