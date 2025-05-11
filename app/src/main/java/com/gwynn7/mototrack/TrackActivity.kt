package com.gwynn7.mototrack

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
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
import androidx.core.app.NotificationCompat
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
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
import kotlin.math.roundToInt
import kotlin.math.sqrt

class TrackActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener, LocationListener {
    private lateinit var binding: ActivityTrackBinding

    private lateinit var mMap: GoogleMap
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
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

    private var leanTracker: LeanTracker = LeanTracker()
    private lateinit var rideDao: RideDAO

    private var calibrated = false
    private var startedMoving = false

    private lateinit var ride: Ride
    private lateinit var rideData: MutableList<RideData>
    private var fuelStations: MutableList<Marker> = mutableListOf()

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

        pauseButton = findViewById<ImageButton>(R.id.pauseButton)
        pauseButton.setOnClickListener {
            showSaveWidget()
            notificationManager.cancelAll()
            Toast.makeText(this, "Tracking paused", Toast.LENGTH_SHORT).show()
        }
        saveButton = findViewById<ImageButton>(R.id.saveButton)
        saveButton.setOnClickListener {
            if(ride.totalDistance < 1000 || rideData.isEmpty())
            {
                Toast.makeText(this, "Ride too short to be saved", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            mMap.addMarker(
                MarkerOptions()
                    .position(LatLng(rideData.last().latitude, rideData.last().longitude))
                    .title("End")
            )
            screenshotAndSave()
        }
        resumeButton = findViewById<ImageButton>(R.id.resumeButton)
        resumeButton.setOnClickListener {
            showMapWidget()
            Toast.makeText(this, "Tracking restarted", Toast.LENGTH_SHORT).show()
        }
        deleteButton = findViewById<ImageButton>(R.id.deleteButton)
        deleteButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }

        leanText = findViewById<TextView>(R.id.leanText)
        speedText = findViewById<TextView>(R.id.speedText)
        messageText = findViewById<TextView>(R.id.messageText)

        calibrationButton = findViewById<Button>(R.id.calibrationButton)
        calibrationButton.isEnabled = false
        calibrationButton.setOnClickListener {
            val timer = object : CountDownTimer(4500, 500) {
                override fun onTick(millisUntilFinished: Long) {
                    if(millisUntilFinished <= 1000) messageText.text = "Let's Ride!"
                }

                override fun onFinish() {
                    leanTracker.stopCalibration()
                    calibrated = true

                    ride.startTime = System.currentTimeMillis()

                    calibrationLayout.visibility = View.GONE
                    mapLayout.visibility = View.VISIBLE
                    mapWidget.visibility = View.VISIBLE

                    val startNotification = NotificationCompat.Builder(this@TrackActivity, "motoTrack")
                        .setSmallIcon(R.drawable.logo)
                        .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.logo))
                        .setContentTitle("Ride Started")
                        .setContentText("Enjoy your ride!")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setDefaults(Notification.DEFAULT_ALL)
                        .setAutoCancel(true)
                        .build()
                    notificationManager.notify(0, startNotification)
                }
            }

            leanTracker.startCalibration()
            calibrationButton.isEnabled = false
            calibrationButton.text = "Calibrating..."
            messageText.text = "Lean your bike left and right"
            timer.start()
        }

        sensorManager = this.getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = this.getSystemService(LOCATION_SERVICE) as LocationManager
        notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        accelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        ride = Ride()
        rideData = mutableListOf()

        mapView = findViewById<FragmentContainerView>(R.id.map)
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    showSaveWidget()
                }
            }
        )

        val channel = NotificationChannel(
            "motoTrack",
            "Ride Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Channel for ride notifications"
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
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
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 750, 0f, this)
    }

    fun stopTracking()
    {
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.isMyLocationEnabled = true
        mMap.isIndoorEnabled = false
        mMap.mapType = GoogleMap.MAP_TYPE_HYBRID
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if(!calibrated) return
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
                lastMaxLean = lastMaxLean.coerceAtLeast(lean)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onLocationChanged(newLocation: Location) {
        calibrationButton.isEnabled = (true && !leanTracker.isCalibrating)
        if(calibrationButton.isEnabled) {
            calibrationButton.text = "Calibrate and Start"
            messageText.visibility = View.VISIBLE
        }

        if(!calibrated) return

        val newCoordinates = LatLng(newLocation.latitude, newLocation.longitude)
        val speed = if(newLocation.hasSpeed()) (newLocation.speed * 3.6f).roundToInt() else lastRideData?.speed ?: 0
        val altitude = if(newLocation.hasAltitude()) newLocation.altitude else lastRideData?.altitude ?: 0.0

        speedText.text = "%d".format(speed)

        if(!startedMoving)
        {
            if(speed > 0) startedMoving = true
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
                val isNotificationActive = activeNotifications.any { it.id == 1 }
                if (!isNotificationActive) {
                    val notification = NotificationCompat.Builder(this, "motoTrack")
                        .setSmallIcon(R.drawable.logo)
                        .setContentTitle("Motorcycle Stopped")
                        .setContentText("Remember your kickstand when you get off!")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setDefaults(Notification.DEFAULT_ALL)
                        .setOngoing(true)
                        .build()
                    notificationManager.notify(1, notification)

                    getNearbyFuelStations(newLocation)
                }
                return
            }
            else if(speed >= 10) {
                fuelStations.forEach { it.remove() }
                fuelStations.clear()
                notificationManager.cancelAll()
            }
        }

        val newData = RideData(id=0, rideId = -1, newCoordinates.latitude, newCoordinates.longitude, altitude, lastMaxAcceleration, speed, lastMaxLean)
        rideData.add(newData)
        currentLocation?.let { ride.totalDistance += it.distanceTo(newLocation) }

        val cameraPosition = CameraPosition.Builder()
            .target(newCoordinates)
            .zoom(17f)
            .bearing(if(newLocation.hasBearing()) newLocation.bearing else currentLocation?.bearing ?: 0f)
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
            .addQueryParameter("key", "AIzaSyCNor3A8rCY0IzuwHfbtLtW-LnGboYCzwM")
            .build()

        val request = Request.Builder().url(url).build()

        val client = OkHttpClient()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("PlacesAPI", "Request failed: ${e.message}")
            }

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


                            val builder = LatLngBounds.Builder()
                            fuelStations.forEach { builder.include(it.position) }
                            val bounds = builder.build()
                            val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 100)
                            mMap.moveCamera(cameraUpdate)
                        }
                    }
                }
            }
        })
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

    fun saveAndExit() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ride.endTme = System.currentTimeMillis()
                val rideId = rideDao.insertRide(ride)
                rideData.forEach { it.rideId = rideId }
                rideDao.insertRideData(rideData)
            }

            val intent = Intent(this@TrackActivity, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
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

    fun showSaveWidget() {
        stopTracking()

        mapWidget.visibility = View.GONE
        saveWidget.visibility = View.VISIBLE
    }

    fun showMapWidget() {
        startTracking()

        mapWidget.visibility = View.VISIBLE
        saveWidget.visibility = View.GONE
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}