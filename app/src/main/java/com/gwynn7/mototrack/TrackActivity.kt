package com.gwynn7.mototrack

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchNearbyRequest
import com.gwynn7.mototrack.databinding.ActivityTrackBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.isGone
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class TrackActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var binding: ActivityTrackBinding

    private lateinit var loadingLayout: FrameLayout
    private lateinit var mapLayout: FrameLayout
    private lateinit var mapView: FrameLayout
    private lateinit var mapWidget: FrameLayout
    private lateinit var saveWidget: FrameLayout

    private lateinit var leanText: TextView
    private lateinit var speedText: TextView

    private lateinit var calibrationLayout: FrameLayout
    private lateinit var calibrationButton: Button

    private lateinit var pauseButton: ImageButton
    private lateinit var saveButton: ImageButton
    private lateinit var resumeButton: ImageButton
    private lateinit var deleteButton: ImageButton

    private lateinit var mMap: GoogleMap
    private var placesClient: PlacesClient? = null

    private var trackingService: TrackingService? = null
    private var isBound = false

    private lateinit var sharedPrefs: SharedPreferences
    private var leanTrackingEnabled: Boolean = true
    private var fuelStationsEnabled: Boolean = true
    private var fuelStationsRange: Double = 2000.0
    private var fuelStationsNumber: Int = 10
    private var speedWarning: Int? = null
    private var leanWarning: Int? = null
    private lateinit var defaultTextColor: ColorStateList

    private var fuelStations: MutableList<Marker> = mutableListOf()
    private var lastParkingLocation: Marker? = null
    private var activityState: TrackActivityState = TrackActivityState.SearchingGps

    private val callback = object : OnBackPressedCallback(true) {
        //Override "back" for custom behaviour based on different state of the activity
        override fun handleOnBackPressed() {
            when(activityState) {
                TrackActivityState.SearchingGps -> quit()
                TrackActivityState.Calibrating -> quit()
                TrackActivityState.Tracking -> stopTracking()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //Set flag to keep the screen on while tracking the ride
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        //Setup binding and action bar
        binding = ActivityTrackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        //Add callback for "back" press
        onBackPressedDispatcher.addCallback(this, callback)

        calibrationLayout = findViewById<FrameLayout>(R.id.calibrationLayout)
        mapLayout = findViewById<FrameLayout>(R.id.mapLayout)
        loadingLayout = findViewById<FrameLayout>(R.id.loadingLayout)

        mapWidget = findViewById<FrameLayout>(R.id.mapWidget)
        saveWidget = findViewById<FrameLayout>(R.id.saveWidget)

        calibrationButton = findViewById<Button>(R.id.calibrationButton)
        resumeButton = findViewById<ImageButton>(R.id.resumeButton)
        pauseButton = findViewById<ImageButton>(R.id.pauseButton)
        saveButton = findViewById<ImageButton>(R.id.saveButton)
        deleteButton = findViewById<ImageButton>(R.id.deleteButton)

        //Setup listener for buttons click
        calibrationButton.setOnClickListener { calibrate() }
        resumeButton.setOnClickListener { startTracking() }
        pauseButton.setOnClickListener{ stopTracking() }
        saveButton.setOnClickListener { saveRide() }
        deleteButton.setOnClickListener{ quit() }

        leanText = findViewById<TextView>(R.id.leanText)
        speedText = findViewById<TextView>(R.id.speedText)
        defaultTextColor = leanText.textColors

        //Load Map from map fragment
        mapView = findViewById<FragmentContainerView>(R.id.map)
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        //Get API KEY for the "Google Places" API and setup client
        val apiKey = BuildConfig.MAPS_API_KEY
        if (apiKey.isNotEmpty()) {
            Places.initializeWithNewPlacesApiEnabled(applicationContext, apiKey)
            placesClient = Places.createClient(this)
        }

        //Load settings
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        speedWarning = if(sharedPrefs.getBoolean("show_speed_warning", true)) sharedPrefs.getString("speed_warning", "160")?.toInt() else null
        leanWarning = if(sharedPrefs.getBoolean("show_lean_warning", false)) sharedPrefs.getString("lean_warning", "30")?.toInt() else null
        fuelStationsEnabled = sharedPrefs.getBoolean("show_fuel_stations", fuelStationsEnabled)
        fuelStationsRange = sharedPrefs.getString("fuel_stations_range", fuelStationsRange.toString())!!.toDouble()
        fuelStationsNumber = sharedPrefs.getString("fuel_stations_number", fuelStationsNumber.toString())!!.toInt()
        leanTrackingEnabled = sharedPrefs.getBoolean("lean_track_enabled", leanTrackingEnabled)

        //Restore state if activity was stopped by the OS
        val savedState = savedInstanceState?.getString("activityState")
        if (savedState != null) {
            activityState = TrackActivityState.valueOf(savedState)
        }

        //Request optional background location permission
        requestBackgroundPermission()
    }

    override fun onStart() {
        super.onStart()

        //Start the tracking service as a foreground standalone service, and then bind it to the activity
        val trackingIntent = Intent(this, TrackingService::class.java)
        startForegroundService(trackingIntent)
        bindService(trackingIntent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()

        //Unbind service from the activity on close (but keep the service alive)
        unbindService()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        //Save current activity state in the bundle
        outState.putString("activityState", activityState.name)
    }

    //Functions to stop/unbind the tracking service

    private fun unbindService(){
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun stopService() {
        unbindService()
        val intentService = Intent(this, TrackingService::class.java)
        stopService(intentService)
    }

    private fun quit(){
        stopService()
        finish()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val binderService = binder as TrackingService.LocalBinder
            trackingService = binderService.getService()
            isBound = true

            //Setup the view based on the state of the activity
            when(activityState) {
                TrackActivityState.SearchingGps -> checkGps()
                TrackActivityState.Calibrating -> showCalibration()
                TrackActivityState.Tracking ->
                    {
                    showMap()

                    //Redraw map if activity was killed or refreshed
                    lifecycleScope.launch {
                        val data = trackingService!!.rideData.toList()
                        if(data.isEmpty()) return@launch

                        val numPointsPerSec = max(data.size / 2000, 1)
                        var index = 1
                        while(index != data.size){
                            //Loops to animate drawing of the lines and reduce cpu load
                            for(i in 0 until numPointsPerSec)
                            {
                                drawPolyline(LatLng(data[index-1].latitude, data[index-1].longitude), LatLng(data[index].latitude, data[index].longitude))
                                index++
                            }
                            delay(2)
                        }

                        drawStartMarker(LatLng(data.first().latitude, data.first().longitude))
                    }

                    if(trackingService!!.shouldShowFuelStations() && fuelStationsEnabled) {
                        getNearbyFuelStations(trackingService!!.lastLocation!!)
                    }
                    if(trackingService!!.lastParkingLocation != null) {
                        showLastParkingLocation(trackingService!!.lastParkingLocation!!)
                    }
                }
            }

            //Callbacks from the service to the activity

            trackingService?.setListener(object : ServiceListener {
                override fun onUpdateLeanAngle(angle: Int) {
                    runOnUiThread {
                        leanText.text = "%d°".format(angle)
                        leanWarning?.let {
                            if(angle >= it) leanText.setTextColor(Color.RED)
                            else leanText.setTextColor(defaultTextColor)
                        }
                    }
                }

                override fun onUpdateSpeed(speed: Int) {
                    runOnUiThread {
                        speedText.text = "%d".format(speed)
                        speedWarning?.let {
                            if(speed >= it) speedText.setTextColor(Color.RED)
                            else speedText.setTextColor(defaultTextColor)
                        }
                    }
                }

                override fun onGPSFound() {
                    runOnUiThread {
                        showCalibration()
                    }
                }

                override fun onUpdateLocation(lastLocation: Location?, newLocation: Location) {
                    val newCoordinates = LatLng(newLocation.latitude, newLocation.longitude)
                    val cameraPosition = CameraPosition.Builder()
                        .target(newCoordinates)
                        .bearing(if(newLocation.hasBearing()) newLocation.bearing else lastLocation?.bearing ?: 0f)
                        .zoom(16f)
                        .tilt(45f)
                        .build()

                    //Update camera position and draw the new line on the map
                    runOnUiThread {
                        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))

                        if(lastLocation != null) {
                            drawPolyline(LatLng(lastLocation.latitude, lastLocation.longitude), LatLng(newLocation.latitude, newLocation.longitude))
                        }
                    }
                }

                override fun onRideStarted(location: Location) {
                    runOnUiThread {
                        drawStartMarker(LatLng(location.latitude, location.longitude))
                    }
                }

                override fun onMotorcycleStopped(location: Location) {
                    runOnUiThread {
                        getNearbyFuelStations(location) //Search fuel stations when bike stops
                    }
                }

                override fun onMotorcycleMoving() {
                    runOnUiThread {
                        removeFuelStations()
                    }
                }

                override fun onWorkDone() {
                    quit()
                }
            })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            trackingService = null
        }
    }

    private fun calibrate(){
        val calibrationTimer = object : CountDownTimer(6500, 250) {
            //Calibration steps regulated by a timer
            override fun onTick(millisUntilFinished: Long) {
                if(millisUntilFinished <= 1000) {
                    calibrationButton.text = getString(R.string.enjoy_the_ride)
                }
                else if(millisUntilFinished <= 4000) {
                    trackingService?.startCalibration()
                    calibrationButton.text = getString(R.string.accelerate_forward)
                }
            }

            override fun onFinish() {
                trackingService?.endCalibration()
                showMap()
            }
        }

        activityState = TrackActivityState.Calibrating
        calibrationButton.isEnabled = false

        calibrationButton.text = getString(R.string.upright_your_bike)
        calibrationTimer.start()
    }

    @SuppressLint("MissingPermission")
    private fun startTracking()
    {
        //Update view and signal the service to start tracking
        mapWidget.visibility = View.VISIBLE
        saveWidget.visibility = View.GONE
        lastParkingLocation?.hideInfoWindow()

        trackingService?.startTracking()
    }

    private fun stopTracking()
    {
        //Update view and signal the service to stop tracking
        trackingService?.stopTracking()

        mapWidget.visibility = View.GONE
        saveWidget.visibility = View.VISIBLE

        removeFuelStations()
        val lastLocation = trackingService?.lastLocation
        if(lastLocation != null){
            showLastParkingLocation(lastLocation)
        }
    }

    private fun showLastParkingLocation(location: Location) {
        lastParkingLocation?.remove()
        lastParkingLocation = mMap.addMarker(
            MarkerOptions()
                .position(LatLng(location.latitude, location.longitude))
                .title(getString(R.string.last_parking_location))
                .icon(bitmapDescriptorFromVector(applicationContext, R.drawable.parking))
        )
        lastParkingLocation?.showInfoWindow()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.isMyLocationEnabled = true
        mMap.isIndoorEnabled = false
        mMap.mapType = GoogleMap.MAP_TYPE_HYBRID
    }

    //Functions to update the view based on the state of the activity

    private fun checkGps()
    {
        if(trackingService?.gpsFound == true) showCalibration()
        else {
            calibrationLayout.visibility = View.VISIBLE

            mapLayout.visibility = View.GONE
            mapWidget.visibility = View.GONE
            saveWidget.visibility = View.GONE
        }
    }

    private fun showCalibration()
    {
        trackingService?.resetCalibration()
        if(!leanTrackingEnabled)
        {
            trackingService?.endCalibration()
            showMap()
            return
        }

        calibrationLayout.visibility = View.VISIBLE
        calibrationButton.isEnabled = true
        calibrationButton.text = getString(R.string.calibrate_and_start)

        mapLayout.visibility = View.GONE
        mapWidget.visibility = View.GONE
        saveWidget.visibility = View.GONE

        activityState = TrackActivityState.Calibrating
    }

    private fun showMap()
    {
        calibrationLayout.visibility = View.GONE
        mapLayout.visibility = View.VISIBLE
        mapWidget.visibility = if(trackingService?.trackingActive == true) View.VISIBLE else View.GONE
        saveWidget.visibility = if(mapWidget.isGone) View.VISIBLE else View.GONE

        activityState = TrackActivityState.Tracking
    }

    //Functions to draw on the map

    private fun drawPolyline(lastLocation: LatLng, newLocation: LatLng) {
        val polylineOptions = PolylineOptions()
            .add(LatLng(lastLocation.latitude, lastLocation.longitude), LatLng(newLocation.latitude, newLocation.longitude))
            .width(8f)
            .color(Color.CYAN)
            .geodesic(true)

        mMap.addPolyline(polylineOptions)
    }

    private fun drawStartMarker(location: LatLng) {
        mMap.addMarker(
            MarkerOptions()
                .position(location)
                .title(getString(R.string.start))
                .icon(bitmapDescriptorFromVector(applicationContext, R.drawable.location_start))
        )
    }

    private fun getNearbyFuelStations(location: Location) {
        if(placesClient == null || !fuelStationsEnabled) return

        //Setup parameters for the call of the Places API

        val placeFields = listOf(Place.Field.DISPLAY_NAME, Place.Field.LOCATION)
        val bounds = CircularBounds.newInstance(LatLng(location.latitude, location.longitude), fuelStationsRange)
        val types = listOf("gas_station")
        val searchNearby = SearchNearbyRequest.builder(bounds, placeFields)
            .setIncludedTypes(types)
            .setMaxResultCount(fuelStationsNumber)
            .build()

        placesClient!!.searchNearby(searchNearby)
            .addOnSuccessListener { response ->
                val places: List<Place?>? = response.places
                if(places == null) return@addOnSuccessListener
                //Launch a coroutine on the main thread to update the UI
                lifecycleScope.launch(Dispatchers.Main) {
                    for (place in places) {
                        if(place == null || place.location == null) continue
                        val stationLocation = Location("").apply {
                            latitude = place.location!!.latitude
                            longitude = place.location!!.longitude
                        }

                       val marker = mMap.addMarker(
                           MarkerOptions()
                               .position(place.location!!)
                               .title(place.displayName)
                               .snippet("%.2f km".format(location.distanceTo(stationLocation) / 1000))
                               .icon(bitmapDescriptorFromVector(applicationContext, R.drawable.gas_station))
                        )

                        if(fuelStations.isEmpty()) marker?.showInfoWindow()
                        marker?.let { fuelStations.add(it) }
                    }
                    //Update the camera zoom to show all fuel stations
                    if(fuelStations.isNotEmpty()) {
                        val builder = LatLngBounds.Builder()
                        fuelStations.forEach { builder.include(it.position) }
                        builder.include(LatLng(location.latitude, location.longitude))
                        val cameraUpdate = CameraUpdateFactory.newLatLngBounds(builder.build(), 100)
                        mMap.animateCamera(cameraUpdate)
                    }
                }
            }
    }

    private fun removeFuelStations(){
        fuelStations.forEach { it.remove() }
        fuelStations.clear()
    }

    private fun saveRide(){
        if(trackingService == null) return
        if(trackingService!!.ride.totalDistance < 1000)
        {
            Toast.makeText(this, getString(R.string.ride_too_short), Toast.LENGTH_LONG).show()
            return
        }
        //Clear the map, set the "End" marker and screenshot the map
        removeFuelStations()
        lastParkingLocation?.remove()
        mMap.addMarker(
            MarkerOptions()
                .position(LatLng(trackingService!!.rideData.last().latitude, trackingService!!.rideData.last().longitude))
                .title(getString(R.string.end))
                .icon(bitmapDescriptorFromVector(applicationContext, R.drawable.location_end))
        )
        screenshotAndSave()
    }

    private fun screenshotAndSave() {
        loadingLayout.visibility = View.VISIBLE
        mapWidget.visibility = View.GONE

        //Change the size of the map to make a square screenshot
        val newSize = mapView.layoutParams
        newSize.width = Resources.getSystem().displayMetrics.widthPixels
        newSize.height = newSize.width
        mapView.layoutParams = newSize
        mapView.invalidate() //Invalidate the view to update the map

        //Setup the camera zoom on the map for the screenshot
        val locationsList = trackingService!!.rideData
        if(locationsList.isNotEmpty())
        {
            val builder = LatLngBounds.Builder()
            locationsList.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
            val bounds = builder.build()
            val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 250)
            mMap.moveCamera(cameraUpdate)
        }

        Toast.makeText(this, getString(R.string.screenshot), Toast.LENGTH_SHORT).show()
        //When map is ready, take the screenshot
        mMap.setOnMapLoadedCallback {
            mMap.snapshot { saveMapSnapshot(it) }
        }
    }

    private fun saveMapSnapshot(bitmap: Bitmap?) {
        if(bitmap != null) {
            //Launch a coroutine to save the image on a different thread
            lifecycleScope.launch{
                //Save image in the storage on a different thread
                val imageName = withContext(Dispatchers.IO){
                    val filename = "map_snapshot_${System.currentTimeMillis()}.png"
                    val file = File(applicationContext.filesDir, filename)
                    val outputStream = FileOutputStream(file)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 50, outputStream)
                    outputStream.flush()
                    outputStream.close()
                    file.absolutePath
                }
                trackingService?.saveRide(imageName) //Communicate to the service the image name to save the ride
            }
        }
        else trackingService?.saveRide(null)
    }

    private fun requestBackgroundPermission() {
        val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(applicationContext,
                getString(R.string.permission_granted), Toast.LENGTH_SHORT).show()
        }
        //Launch permissions popup or redirect to settings if permission was previously denied by the user (or always on android 11+ for background location)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                Toast.makeText(applicationContext,
                    getString(R.string.enable_location_allow_all_the_time), Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            else {
                requestPermissions.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }
}