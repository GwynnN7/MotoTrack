package com.gwynn7.mototrack

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.preference.PreferenceManager
import com.google.android.gms.maps.model.LatLng
import com.gwynn7.mototrack.database.Ride
import com.gwynn7.mototrack.database.RideDAO
import com.gwynn7.mototrack.database.RideData
import com.gwynn7.mototrack.database.RideDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class TrackingService : Service(), SensorEventListener, LocationListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var vibrator: Vibrator

    private var accelerationSensor: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var leanTracker: LeanTracker = LeanTracker()

    private lateinit var rideDao: RideDAO
    private lateinit var sharedPrefs: SharedPreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO) //Create a coroutine scope for the service
    private val handler = Handler(Looper.getMainLooper())

    private var startedMoving = false
    private var pollingRate = 750L
    private var leanTrackingEnabled = true

    private var lastRideData: RideData? = null
    private var lastMaxLean: Int = 0
    private var lastMaxAcceleration: Float = 0f

    var ride: Ride = Ride()
    var rideData: MutableList<RideData> = mutableListOf()
    var lastLocation: Location? = null
    var lastParkingLocation: Location? = null

    var gpsFound = false
    var trackingActive = false
    var isCalibrated = false
    var motorcycleMoving = false

    private val binder = LocalBinder()
    private var serviceListener: ServiceListener? = null

    companion object {
        private var notificationImage: Bitmap? = null

        fun getNotificationImage(context: Context): Bitmap? {
            if (notificationImage == null) {
                notificationImage = BitmapFactory.decodeResource(context.resources, R.drawable.notification_big)
            }
            return notificationImage
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    fun setListener(listener: ServiceListener) {
        serviceListener = listener
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()

        //Load database
        val db = RideDatabase.getInstance(this)
        rideDao = db.rideDao()

        //Load settings
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        pollingRate = sharedPrefs.getString("polling_rate", pollingRate.toString())!!.toLong()
        leanTrackingEnabled = sharedPrefs.getBoolean("lean_track_enabled", leanTrackingEnabled)

        //Setup services
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = applicationContext.getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            applicationContext.getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        //Setup sensors
        accelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        //Setup notification channel
        val channel = NotificationChannel(getString(R.string.notificationChannel), getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = getString(R.string.channel_for_ride_notifications)
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)

        //Start the service as a foreground service, with the created notification
        startForeground(46, getServiceNotification())

        //Start tracking the ride
        startTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        if(trackingActive) stopTracking()
        handler.removeCallbacks(runnableTimer) //Interrupt timer of notification update
        notificationManager.cancelAll() //Destroy all the other notifications
        serviceScope.cancel() //Destroy the coroutine scope
    }

    fun buildNotification(title: String, text: String, onGoing: Boolean): Notification {
        //Setup intent for open the activity by clicking on the icon (launch the open activity if present)
        val intent = Intent(this, TrackActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(applicationContext, getString(R.string.notificationChannel))
            .setSmallIcon(R.drawable.bike_notification)
            .setLargeIcon(getNotificationImage(applicationContext))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(onGoing)
            .setOnlyAlertOnce(true)
            .build()
    }

    //Function to create the notification based on the state of the ride
    private fun getServiceNotification(): Notification {
        var title = getString(R.string.tracking_ready)
        if(isCalibrated)
        {
            if(trackingActive) {
                ride.totalSeconds++
                title = getString(R.string.tracking_active)
            }
            else  title = getString(R.string.tracking_paused)
        }
        var text = if(isCalibrated) formatTime(ride.totalSeconds) else getString(R.string.tracking_will_start)

        return buildNotification(title, text, true)
    }

    @SuppressLint("MissingPermission")
    fun startTracking()
    {
        //Start reading sensor data
        accelerationSensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        if(leanTrackingEnabled)
        {
            gravitySensor?.also {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
        //Start requesting phone location
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, pollingRate, 0f, this)

        trackingActive = isCalibrated

        //Vibrate phone on tracking start
        val effect = VibrationEffect.createWaveform(longArrayOf(0L, 200L, 100L, 200L), -1)
        vibrator.vibrate(effect)
    }

    fun stopTracking()
    {
        //Unregister sensors reading and location requests
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)

        trackingActive = false
        motorcycleMoving = false
        lastParkingLocation = lastLocation

        //Vibrate phone on tracking stop
        val effect = VibrationEffect.createWaveform(longArrayOf(0L, 50L, 50L, 50L), -1)
        vibrator.vibrate(effect)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if((!isCalibrated || !trackingActive) && !leanTracker.isCalibrating) return
        when (event?.sensor?.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                leanTracker.addAcceleration(event.values)

                val acceleration = Vector3.fromArray(event.values)
                //Update max acceleration since last saved rideData
                lastMaxAcceleration = lastMaxAcceleration.coerceAtLeast(acceleration.magnitude())
            }
            Sensor.TYPE_GRAVITY -> {
                leanTracker.addGravity(event.values)

                val lean = leanTracker.calculateLeanAngle(event.values)
                //Send lean angle to the activity
                serviceListener?.onUpdateLeanAngle(lean)
                //Update max lean since last saved rideData
                lastMaxLean = lastMaxLean.coerceAtLeast(lean)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun startCalibration() {
        leanTracker.startCalibration() //Start reading data for lean angle calibration
    }

    fun resetCalibration(){
        leanTracker.resetCalibration()
        isCalibrated = false
    }

    fun endCalibration() {
        leanTracker.stopCalibration() //Stop reading data for calibration and start reading data for tracking
        trackingActive = true
        isCalibrated = true

        lastMaxLean = 0
        lastMaxAcceleration = 0f
    }


    @SuppressLint("MissingPermission")
    override fun onLocationChanged(newLocation: Location) {
        if(!gpsFound)
        {
            serviceListener?.onGPSFound() //Signal the activity that GPS signal was found
            gpsFound = true
        }
        if(!isCalibrated) return

        val newCoordinates = LatLng(newLocation.latitude, newLocation.longitude)
        val speed = if(newLocation.hasSpeed()) (newLocation.speed * 3.6f).roundToInt() else lastRideData?.speed ?: 0
        val altitude = if(newLocation.hasAltitude()) newLocation.altitude else lastRideData?.altitude ?: 0.0

        serviceListener?.onUpdateSpeed(speed) //Send speed value to the activity

        if(!startedMoving)
        {
            if(speed > 0) {
                startedMoving = true
                startStopWatch() //Start ride time counter when motorcycle starts moving
            }
            if(rideData.isNotEmpty()) return
            serviceListener?.onRideStarted(newLocation) //Signal activity that the ride started
        }
        else
        {
            if(speed == 0) {
                //If bike stopped, send notification and signal the activity
                if (motorcycleMoving) {
                    val notification = buildNotification(getString(R.string.motorcycle_stopped), getString(R.string.kickstand), false)
                    notificationManager.notify(117, notification)

                    serviceListener?.onMotorcycleStopped(newLocation)
                    motorcycleMoving = false
                }
                return
            }
            else if(speed > 15) {
                //If bike started moving again, remove notification and signal the activity
                notificationManager.cancel(117)

                serviceListener?.onMotorcycleMoving()
                motorcycleMoving = true
            }
        }

        //Save last sample of rideData and signal the activity to update the map
        val newData = RideData(id=0, rideId = -1, newCoordinates.latitude, newCoordinates.longitude, altitude, lastMaxAcceleration, speed, lastMaxLean)
        rideData.add(newData)
        lastLocation?.let { ride.totalDistance += it.distanceTo(newLocation) }
        serviceListener?.onUpdateLocation(lastLocation, newLocation)

        lastMaxLean = 0
        lastMaxAcceleration = 0f
        lastRideData = newData
        lastLocation = newLocation
    }

    private val runnableTimer = object : Runnable {
        //Update the timer on the notification each second
        override fun run() {
            notificationManager.notify(46, getServiceNotification())
            handler.postDelayed(this, 1000)
        }
    }

    private fun startStopWatch() {
        ride.startTime = System.currentTimeMillis()
        handler.post(runnableTimer) //Pass the runnable to the looper to start counting in background
    }

    fun saveRide(bitmapName: String?) {
        ride.snapshot = bitmapName
        //Launch a coroutine to save the ride in the database
        serviceScope.launch {
            //Down sample data and save ride in the database on a different thread
            withContext(Dispatchers.IO) {
                val rideId = rideDao.insertRide(ride)
                val downSampledData = downSampleData(rideData)
                downSampledData.forEach { it.rideId = rideId }
                rideDao.insertRideData(downSampledData)
            }
            serviceListener?.onWorkDone() //Signal the activity that it can kill the service and finish
        }
    }


    //Algorithm to down sample the number of ride data samples saved, to reduce heaviness and storage space
    private fun downSampleData(data: List<RideData>): List<RideData> {
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
            var maxAcceleration = 0f
            var maxAltitude = 0.0

            //Foreach point, scan the next max 10, and check when statistics change enough to become worth saving
            for(i in 1..10)
            {
                val px = data[index+i]
                val pxLoc = Location("").apply {
                    latitude = px.latitude
                    longitude = px.longitude
                }

                val bearing = p0Loc.bearingTo(pxLoc)
                minBearing = min(minBearing, bearing)

                //Foreach down sampled route, keep max values on that route
                maxSpeed = max(maxSpeed, px.speed)
                maxLean = max(maxLean, px.leanAngle)
                maxAcceleration = max(maxAcceleration, px.acceleration)
                maxAltitude = max(maxAltitude, px.altitude)

                //Clauses that define which sample is worth saving (worst case scenario: down sample 10 to 1)
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
}