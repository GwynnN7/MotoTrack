package com.gwynn7.mototrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TrackingService : Service() {

    private val binder = LocalBinder()
    private var listener: Listener? = null

    inner class LocalBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    interface Listener {
        fun onUpdate(speed: Float, angle: Float, location: Location)
    }

    fun setListener(l: Listener) {
        listener = l
    }

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var isTracking = true

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        initTracking()
    }

    private fun initTracking() {
        // Setup location + sensors
        // Start listeners, then call:
        // listener?.onUpdate(currentSpeed, leanAngle, location)
    }

    fun pauseTracking() {
        isTracking = false
        // Pause listeners
    }

    fun resumeTracking() {
        isTracking = true
        // Resume listeners
    }

    private fun createNotification(): Notification {
        val channelId = "tracking_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Tracking", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tracking ride")
            .setSmallIcon(R.drawable.ic_tracker)
            .build()
    }
}