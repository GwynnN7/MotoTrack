package com.gwynn7.mototrack

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
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
import com.gwynn7.mototrack.database.Ride
import com.gwynn7.mototrack.database.RideDAO
import com.gwynn7.mototrack.database.RideData
import com.gwynn7.mototrack.database.RideDatabase
import com.gwynn7.mototrack.databinding.ActivityTrackBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class TrackActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener, LocationListener {
    private lateinit var binding: ActivityTrackBinding

    private lateinit var mMap: GoogleMap
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var vibrator: Vibrator
    private var accelerationSensor: Sensor? = null
    private var gravitySensor: Sensor? = null

    private lateinit var loadingLayout: FrameLayout
    private lateinit var mapLayout: FrameLayout
    private lateinit var mapView: FrameLayout
    private lateinit var mapWidget: FrameLayout
    private lateinit var saveWidget: FrameLayout

    private lateinit var leanText: TextView
    private lateinit var speedText: TextView
    private lateinit var messageText: TextView

    private lateinit var calibrationLayout: FrameLayout
    private lateinit var calibrationButton: Button

    private lateinit var pauseButton: ImageButton
    private lateinit var saveButton: ImageButton
    private lateinit var resumeButton: ImageButton
    private lateinit var deleteButton: ImageButton

    private var lastRideData: RideData? = null
    private var currentLocation: Location? = null
    private var lastMaxLean: Int = 0
    private var lastMaxAcceleration: Double = 0.0

    private var pollingRate = 750L
    private var speedWarning: Int? = null
    private var leanWarning: Int? = null
    private lateinit var defaultTextColor: ColorStateList

    private var leanTracker: LeanTracker = LeanTracker()
    private lateinit var rideDao: RideDAO
    private lateinit var sharedPrefs: SharedPreferences

    private var calibrated = false
    private var startedMoving = false
    private var trackingActive = false

    private lateinit var ride: Ride
    private lateinit var rideData: MutableList<RideData>
    private val handler = Handler(Looper.getMainLooper())
    private var fuelStations: MutableList<Marker> = mutableListOf()
    private var lastParkingLocation: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityTrackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val db = RideDatabase.getInstance(this)
        rideDao = db.rideDao()

        calibrationLayout = findViewById<FrameLayout>(R.id.calibrationLayout)
        calibrationLayout.visibility = View.VISIBLE

        loadingLayout = findViewById<FrameLayout>(R.id.loadingLayout)
        loadingLayout.visibility = View.GONE

        mapLayout = findViewById<FrameLayout>(R.id.mapLayout)
        mapLayout.visibility = View.GONE

        mapWidget = findViewById<FrameLayout>(R.id.mapWidget)
        mapWidget.visibility = View.GONE
        saveWidget = findViewById<FrameLayout>(R.id.saveWidget)
        saveWidget.visibility = View.GONE

        resumeButton = findViewById<ImageButton>(R.id.resumeButton)
        resumeButton.setOnClickListener { startTracking() }
        pauseButton = findViewById<ImageButton>(R.id.pauseButton)
        pauseButton.setOnClickListener{ stopTracking() }

        saveButton = findViewById<ImageButton>(R.id.saveButton)
        saveButton.setOnClickListener { saveRide() }

        deleteButton = findViewById<ImageButton>(R.id.deleteButton)
        deleteButton.setOnClickListener { finish() }

        leanText = findViewById<TextView>(R.id.leanText)
        speedText = findViewById<TextView>(R.id.speedText)
        messageText = findViewById<TextView>(R.id.messageText)
        defaultTextColor = leanText.textColors

        calibrationButton = findViewById<Button>(R.id.calibrationButton)
        calibrationButton.setOnClickListener { calibrate() }
        calibrationButton.isEnabled = false

        mapView = findViewById<FragmentContainerView>(R.id.map)
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = applicationContext.getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            applicationContext.getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        accelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        val channel = NotificationChannel("motoTrack", "Ride Notifications", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Channel for ride notifications"
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)

        onBackPressedDispatcher.addCallback(
            this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if(calibrated) stopTracking()
                }
            }
        )

        ride = Ride()
        rideData = mutableListOf()
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        pollingRate = sharedPrefs.getString("polling_rate", pollingRate.toString())!!.toLong()
        speedWarning = if(sharedPrefs.getBoolean("speed_warning", true)) sharedPrefs.getString("speed_warning_list", "180")?.toInt() else null
        leanWarning = if(sharedPrefs.getBoolean("lean_warning", false)) sharedPrefs.getString("lean_warning_list", "40")?.toInt() else null
    }

    //Tracking functions

    override fun onDestroy() {
        super.onDestroy()

        notificationManager.cancelAll()
        handler.removeCallbacks(runnableTimer)
    }

    override fun onResume() {
        super.onResume()
        startTracking()
    }

    override fun onPause() {
        super.onPause()
        stopTracking()
    }

    @SuppressLint("MissingPermission")
    fun startTracking()
    {
        accelerationSensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        gravitySensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        val pollingRate = sharedPrefs.getString("polling_rate", "750")!!.toLong()
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, pollingRate, 0f, this)

        mapWidget.visibility = View.VISIBLE
        saveWidget.visibility = View.GONE
        trackingActive = true

        lastParkingLocation?.hideInfoWindow()

        val effect = VibrationEffect.createWaveform(longArrayOf(0L, 100L, 100L, 100L), -1)
        vibrator.vibrate(effect)
    }

    fun stopTracking()
    {
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)

        mapWidget.visibility = View.GONE
        saveWidget.visibility = View.VISIBLE
        trackingActive = false

        removeFuelStations()

        if(currentLocation != null){
            lastParkingLocation?.remove()
            lastParkingLocation = mMap.addMarker(
                MarkerOptions()
                    .position(LatLng(currentLocation!!.latitude, currentLocation!!.longitude))
                    .title("Last Parking Location")
            )
            lastParkingLocation?.showInfoWindow()
        }

        val effect = VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }

    //------------------

    //Sensors functions

    override fun onSensorChanged(event: SensorEvent?) {
        if(!calibrated || !trackingActive) return
        when (event?.sensor?.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val accel = sqrt(ax * ax + ay * ay + az * az).toDouble()

                lastMaxAcceleration = lastMaxAcceleration.coerceAtLeast(accel)
            }
            Sensor.TYPE_GRAVITY -> {
                val lean = leanTracker.getLeanAngleGravity(event.values)

                leanText.text = "%d°".format(lean)
                leanWarning?.let {
                    if(lean >= it) leanText.setTextColor(Color.RED)
                    else leanText.setTextColor(defaultTextColor)
                }
                lastMaxLean = lastMaxLean.coerceAtLeast(lean)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun calibrate(){
        val timer = object : CountDownTimer(4500, 500) {
            override fun onTick(millisUntilFinished: Long) {
                if(millisUntilFinished <= 1000) messageText.text = "Let's Ride!"
            }

            override fun onFinish() {
                leanTracker.stopCalibration()
                trackingActive = true
                calibrated = true

                calibrationLayout.visibility = View.GONE
                mapLayout.visibility = View.VISIBLE
                mapWidget.visibility = View.VISIBLE
            }
        }

        calibrationButton.isEnabled = false

        leanTracker.startCalibration()
        timer.start()

        calibrationButton.text = "Calibrating..."
        messageText.text = "Lean your bike left and right"
    }

    //------------------

    //Map functions

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.isMyLocationEnabled = true
        mMap.isIndoorEnabled = false
        mMap.mapType = GoogleMap.MAP_TYPE_HYBRID
    }

    @SuppressLint("MissingPermission")
    override fun onLocationChanged(newLocation: Location) {
        calibrationButton.isEnabled = (!calibrated && !leanTracker.isCalibrating)
        if(calibrationButton.isEnabled) {
            calibrationButton.text = "Calibrate and Start"
            messageText.visibility = View.VISIBLE
        }

        if(!calibrated) return

        val newCoordinates = LatLng(newLocation.latitude, newLocation.longitude)
        val speed = if(newLocation.hasSpeed()) (newLocation.speed * 3.6f).roundToInt() else lastRideData?.speed ?: 0
        val altitude = if(newLocation.hasAltitude()) newLocation.altitude else lastRideData?.altitude ?: 0.0

        speedText.text = "%d".format(speed)
        speedWarning?.let {
            if(speed >= it) speedText.setTextColor(Color.RED)
            else speedText.setTextColor(defaultTextColor)
        }

        if(!startedMoving)
        {
            if(speed > 0) {
                startedMoving = true
                startStopWatch()
            }
            if(rideData.isNotEmpty()) return
            mMap.addMarker(
                MarkerOptions()
                    .position(LatLng(newLocation.latitude, newLocation.longitude))
                    .title("Start")
            )
        }
        else
        {
            if(speed == 0) {
                val activeNotifications = notificationManager.activeNotifications
                if (!activeNotifications.any { it.id == 1 }) {
                    val notification = createNotification(this, resources, "Motorcycle Stopped", "Remember your kickstand when you get off!")
                    notification.setOngoing(true)
                    notificationManager.notify(1, notification.build())

                    getNearbyFuelStations(newLocation)
                }
                return
            }
            else if(speed > 20) {
                removeFuelStations()
            }
        }

        val newData = RideData(id=0, rideId = -1, newCoordinates.latitude, newCoordinates.longitude, altitude, lastMaxAcceleration, speed, lastMaxLean)
        rideData.add(newData)
        currentLocation?.let { ride.totalDistance += it.distanceTo(newLocation) }

        val cameraPosition = CameraPosition.Builder()
            .target(newCoordinates)
            .bearing(if(newLocation.hasBearing()) newLocation.bearing else currentLocation?.bearing ?: 0f)
            .zoom(16f)
            .tilt(45f)
            .build()

        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
        drawColoredPath(newData)

        lastMaxLean = 0
        lastMaxAcceleration = 0.0
        lastRideData = newData
        currentLocation = newLocation
    }

    private fun getNearbyFuelStations(location: Location) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("maps.googleapis.com")
            .addPathSegments("maps/api/place/nearbysearch/json")
            .addQueryParameter("location", String.format(Locale.US, "%f,%f", location.latitude, location.longitude))
            .addQueryParameter("radius", "2000")
            .addQueryParameter("type", "gas_station")
            .addQueryParameter("key", BuildConfig.MAPS_API_KEY)
            .build()

        val request = Request.Builder().url(url).build()

        val client = OkHttpClient()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    val json = JSONObject(body)
                    val results = json.getJSONArray("results")
                    lifecycleScope.launch {
                        withContext(Dispatchers.Main){
                            for (i in 0 until results.length()) {
                                val place = results.getJSONObject(i)
                                val name = place.getString("name")
                                val stationLoc = place.getJSONObject("geometry").getJSONObject("location")
                                val lat = stationLoc.getDouble("lat")
                                val lng = stationLoc.getDouble("lng")

                                val stationLocation = Location("").apply {
                                    latitude = lat
                                    longitude = lng
                                }
                                val marker = mMap.addMarker(
                                    MarkerOptions()
                                        .position(LatLng(lat, lng))
                                        .title(name)
                                        .snippet("%.2f km".format(location.distanceTo(stationLocation)))
                                )

                                if(fuelStations.isEmpty()) marker?.showInfoWindow()
                                marker?.let { fuelStations.add(it) }
                            }

                            if(fuelStations.isNotEmpty())
                            {
                                val builder = LatLngBounds.Builder()
                                fuelStations.forEach { builder.include(it.position) }
                                val cameraUpdate = CameraUpdateFactory.newLatLngBounds(builder.build(), 100)
                                mMap.moveCamera(cameraUpdate)
                            }
                        }
                    }
                }
            }
        })
    }

    private fun removeFuelStations(){
        fuelStations.forEach { it.remove() }
        fuelStations.clear()
        notificationManager.cancel(1)
    }

    private fun drawColoredPath(newData: RideData) {
        if(lastRideData != null)
        {
            val polylineOptions = PolylineOptions()
                .add(LatLng(lastRideData!!.latitude, lastRideData!!.longitude), LatLng(newData.latitude, newData.longitude))
                .width(8f)
                .color(Color.CYAN)
                .geodesic(true)

            mMap.addPolyline(polylineOptions)
        }
    }

    //------------------

    //Timer functions

    private val runnableTimer = object : Runnable {
        override fun run() {
            var title ="Tracking Paused"
            if(trackingActive) {
                ride.totalSeconds ++
                title = "Tracking Active"
            }

            val timeFormatted = formatTime(ride.totalSeconds)
            val notification = createNotification(this@TrackActivity, resources, title, timeFormatted)
            notification.setOngoing(true)
            notification.setOnlyAlertOnce(true)
            notificationManager.notify(0, notification.build())

            handler.postDelayed(this, 1000)
        }
    }

    fun startStopWatch() {
        ride.startTime = System.currentTimeMillis()
        handler.post(runnableTimer)
    }

    //------------------

    //Save Ride Functions

    fun saveRide(){
        if(ride.totalDistance < 1000 || rideData.isEmpty())
        {
            Toast.makeText(this, "Ride too short to be saved", Toast.LENGTH_LONG).show()
            return
        }
        removeFuelStations()
        lastParkingLocation?.remove()
        mMap.addMarker(
            MarkerOptions()
                .position(LatLng(rideData.last().latitude, rideData.last().longitude))
                .title("End")
        )
        screenshotAndSave()
    }

    fun screenshotAndSave() {
        loadingLayout.visibility = View.VISIBLE
        mapWidget.visibility = View.GONE

        val newSize = mapView.layoutParams
        newSize.width = Resources.getSystem().displayMetrics.widthPixels
        newSize.height = newSize.width
        mapView.layoutParams = newSize
        mapView.invalidate()

        val builder = LatLngBounds.Builder()
        rideData.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
        val bounds = builder.build()
        val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 250)
        mMap.moveCamera(cameraUpdate)

        Toast.makeText(this, "Taking a screenshot of your ride...", Toast.LENGTH_SHORT).show()
        mMap.setOnMapLoadedCallback {
            mMap.snapshot { bitmap ->
                if(bitmap != null) ride.snapshot = saveMapSnapshot(bitmap)
                saveAndExit()
            }
        }
    }

    fun saveMapSnapshot(bitmap: Bitmap): String? {
        val filename = "map_snapshot_${System.currentTimeMillis()}.png"
        val file = File(applicationContext.filesDir, filename)

        return try {
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 50, outputStream)
            outputStream.flush()
            outputStream.close()
            file.absolutePath
        } catch (e: IOException) {
            null
        }
    }

    fun saveAndExit() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val rideId = rideDao.insertRide(ride)
                val downSampledData = downSampleData(rideData)
                downSampledData.forEach { it.rideId = rideId }
                rideDao.insertRideData(downSampledData)
            }
            finish()
        }
    }

    fun downSampleData(data: List<RideData>): List<RideData> {
        if(data.size < 1000) return data

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
            var maxSpeed = 0
            var maxLean = 0
            var maxAcceleration = 0.0
            var maxAltitude = 0.0

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

    //------------------
}