package com.gwynn7.mototrack.mainActivityFragments

import android.graphics.Color
import androidx.fragment.app.Fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pageType = Pages.fromInt(pageId) ?: Pages.Lean
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

        if(rideData.isEmpty()) return

        val start = LatLng(rideData.first().latitude, rideData.first().longitude)
        val end = LatLng(rideData.last().latitude, rideData.last().longitude)

        val maxIndex = when(pageType)
        {
            Pages.Lean -> rideData.withIndex().maxByOrNull { it.value.leanAngle }
            Pages.Speed -> rideData.withIndex().maxByOrNull { it.value.speed }
            Pages.Acceleration -> rideData.withIndex().maxByOrNull { it.value.acceleration }
            Pages.Altitude -> rideData.withIndex().maxByOrNull { it.value.altitude }
        }?.index ?: 0

        var maxValuePosition = LatLng(rideData[maxIndex].latitude, rideData[maxIndex].longitude)
        if(maxIndex < rideData.lastIndex)
        {
            val newLat = maxValuePosition.latitude + (rideData[maxIndex + 1].latitude - maxValuePosition.latitude) / 2
            val newLong = maxValuePosition.longitude + (rideData[maxIndex + 1].longitude - maxValuePosition.longitude) / 2
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
        rideData.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
        val bounds = builder.build()
        val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 100)
        mMap.moveCamera(cameraUpdate)
        drawRidePath()

        mMap.setOnMapLoadedCallback {
            binding.loadingMapLayout.visibility = View.GONE
        }
    }

    private fun drawRidePath() {
        if(rideData.size < 2) return

        lifecycleScope.launch {
            val polylineData = withContext(Dispatchers.Default) {
                val lines = mutableListOf<PolylineOptions>()
                for (i in 0 until rideData.size - 1) {
                    val p1 = LatLng(rideData[i].latitude, rideData[i].longitude)
                    val p2 = LatLng(rideData[i+1].latitude, rideData[i+1].longitude)

                    val value = when(pageType)
                    {
                        Pages.Lean -> rideData[i].leanAngle
                        Pages.Speed -> rideData[i].speed
                        Pages.Acceleration -> rideData[i].acceleration.roundToInt()
                        Pages.Altitude -> rideData[i].altitude.roundToInt()
                    }
                    val color = getColorFromRange(value)

                    lines.add(
                        PolylineOptions()
                            .clickable(true)
                            .geodesic(true)
                            .add(p1, p2)
                            .color(color)
                            .width(7f)
                    )
                }
                lines
            }

            polylineData.forEach { options ->
                val line = mMap.addPolyline(options)
                line.tag = polylineData.indexOf(options)
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
                        .title(getStatValue(index))
                )
                lastMarker?.remove()
                marker?.showInfoWindow()
                lastMarker = marker
            }
        }
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
        return "Max $name"
    }

    private fun getStatValue(index: Int): String
    {
        return when(pageType)
        {
            Pages.Lean -> "%d°".format(rideData[index].leanAngle)
            Pages.Speed -> "%d km/h".format(rideData[index].speed)
            Pages.Acceleration -> "%.2f m/s²".format(rideData[index].acceleration)
            Pages.Altitude -> "%d m".format(rideData[index].altitude.roundToInt())
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