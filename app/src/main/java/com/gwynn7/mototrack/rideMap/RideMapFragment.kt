package com.gwynn7.mototrack.rideMap

import android.graphics.Color
import androidx.fragment.app.Fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.toColorInt
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
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
import com.gwynn7.mototrack.Pages
import com.gwynn7.mototrack.R
import com.gwynn7.mototrack.bitmapDescriptorFromVector
import com.gwynn7.mototrack.database.RideData
import com.gwynn7.mototrack.databinding.FragmentRideMapBinding
import com.gwynn7.mototrack.getStatsName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class RideMapFragment(): Fragment(), OnMapReadyCallback {
    private var _binding: FragmentRideMapBinding? = null
    private val binding get() = _binding!!

    private lateinit var mMap: GoogleMap
    private var lastMarker: Marker? = null

    private val rideDataViewModel: RideDataViewModel by activityViewModels()
    private var pageType: Pages = Pages.Lean
    private var rideData: List<RideData> = emptyList()

    //Static function to create a new instance of the fragment for the ViewPager
    companion object {
        fun newInstance(index: Int): RideMapFragment {
            val fragment = RideMapFragment()
            fragment.arguments = bundleOf("pageId" to index) //Pass the page index to the fragment
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //Set page type based on the id
        pageType = Pages.fromInt(arguments?.getInt("pageId") ?: 0) ?: Pages.Lean
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentRideMapBinding.inflate(inflater, container, false)
        val root = binding.root

        //Load Map from map fragment
        val mapFragment = childFragmentManager.findFragmentById(R.id.frag_map) as SupportMapFragment
        mapFragment.getMapAsync(this@RideMapFragment)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rideData = rideDataViewModel.rideData //Get rides from the viewModel
    }

    override fun onMapReady(map: GoogleMap) {
        mMap = map
        mMap.mapType = GoogleMap.MAP_TYPE_HYBRID
        mMap.isIndoorEnabled = false

        if(rideData.isEmpty()) return

        val start = LatLng(rideData.first().latitude, rideData.first().longitude)
        val end = LatLng(rideData.last().latitude, rideData.last().longitude)

        //Get index of max value for the specific page
        val maxIndex = when(pageType)
        {
            Pages.Lean -> rideData.withIndex().maxByOrNull { it.value.leanAngle }
            Pages.Speed -> rideData.withIndex().maxByOrNull { it.value.speed }
            Pages.Acceleration -> rideData.withIndex().maxByOrNull { it.value.acceleration }
            Pages.Altitude -> rideData.withIndex().maxByOrNull { it.value.altitude }
        }?.index ?: 0

        var maxValuePosition = LatLng(rideData[maxIndex].latitude, rideData[maxIndex].longitude)
        if(maxIndex > 0)
        {
            //Calculate correct position for max value marker
            val newLat = rideData[maxIndex - 1].latitude + (maxValuePosition.latitude - rideData[maxIndex - 1].latitude) / 2
            val newLong = rideData[maxIndex - 1].longitude + (maxValuePosition.longitude - rideData[maxIndex - 1].longitude) / 2
            maxValuePosition = LatLng(newLat, newLong)
        }

        //Add info markers to the map

        mMap.addMarker(
            MarkerOptions()
                .position(maxValuePosition)
                .title(getMaxStatName())
                .snippet(getStatValue(maxIndex))
                .icon(bitmapDescriptorFromVector(requireContext(), R.drawable.top_location))
        )

       mMap.addMarker(
            MarkerOptions()
                .position(start)
                .title(getString(R.string.start))
                .icon(bitmapDescriptorFromVector(requireContext(), R.drawable.location_start))
        )
        mMap.addMarker(
            MarkerOptions()
                .position(end)
                .title(getString(R.string.end))
                .icon(bitmapDescriptorFromVector(requireContext(), R.drawable.location_end))
        )

        //Setup camera position on the map
        val builder = LatLngBounds.Builder()
        rideData.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
        val bounds = builder.build()
        val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 100)
        mMap.moveCamera(cameraUpdate)

        mMap.setOnMapLoadedCallback {
            //Remove loading screen and draw path on the map
            binding.loadingMapLayout.visibility = View.GONE
            drawRidePath()
        }
    }

    private fun drawRidePath() {
        if(rideData.size < 2) return

        //Launch coroutine to calculate map lines data on a different thread
        lifecycleScope.launch {
            //Calculate polylines on a different thread
            val polylineData = withContext(Dispatchers.Default) {
                val lines = mutableListOf<PolylineOptions>()

                for (i in 1 until rideData.size) {
                    val p1 = LatLng(rideData[i-1].latitude, rideData[i-1].longitude)
                    val p2 = LatLng(rideData[i].latitude, rideData[i].longitude)

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
                            .width(8f)
                    )
                }
                lines
            }

            //Return to main thread to draw the calculated lines on the map
            val numPointsPerSec = max(polylineData.size / 4000, 1)
            var index = 0
            while(index != polylineData.size){
                //Loops to animate drawing of the lines and reduce cpu load
                for(i in 0 until numPointsPerSec) {
                    val line = mMap.addPolyline(polylineData[index])
                    line.tag = index
                    index++
                }
                delay(2)
            }
        }

        //Setup click listener to show info of specific line on the map
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
                    .icon(bitmapDescriptorFromVector(requireContext(), R.drawable.location_click))
            )
            lastMarker?.remove()
            marker?.showInfoWindow()
            lastMarker = marker
        }
    }

    //Functions to get text customized for specific statistics tab

    private fun getMaxStatName(): String {
        val name = when(pageType)
        {
            Pages.Lean -> getStatsName(Pages.Lean.value)
            Pages.Speed -> getStatsName(Pages.Speed.value)
            Pages.Acceleration -> getStatsName(Pages.Acceleration.value)
            Pages.Altitude -> getStatsName(Pages.Altitude.value)
        }
        return "Top ${resources.getString(name)}"
    }

    private fun getStatValue(index: Int): String {
        return when(pageType)
        {
            Pages.Lean -> "%d°".format(rideData[index].leanAngle)
            Pages.Speed -> "%d km/h".format(rideData[index].speed)
            Pages.Acceleration -> "%.2f m/s²".format(rideData[index].acceleration)
            Pages.Altitude -> "%d m".format(rideData[index].altitude.roundToInt())
        }
    }

    //Map colors to specific values of each statistic
    private val colors = arrayOf(Color.CYAN, Color.GREEN, Color.YELLOW, "#FFA500".toColorInt(), Color.RED, Color.MAGENTA)
    private val ranges = mapOf<Pages, Array<Int>>(
        Pages.Lean to arrayOf(10, 20, 30, 40, 50),
        Pages.Speed to arrayOf(40, 80, 120, 160, 200),
        Pages.Acceleration to arrayOf(2, 4, 6, 8, 10),
        Pages.Altitude to arrayOf(100, 400, 800, 1200, 1600)
    )
    //Calculate color from the value of each statistic
    private fun getColorFromRange(value: Int): Int {
        for(i in 0..(colors.size-2)) {
            if(abs(value) < (ranges[pageType]?.get(i) ?: 0)) return colors[i]
        }
        return colors.last()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}