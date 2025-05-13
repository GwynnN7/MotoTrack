package com.gwynn7.mototrack

import android.location.Location

//Interface to communicate between activity and service

interface ServiceListener {
    fun onUpdateLeanAngle(angle: Int)
    fun onUpdateSpeed(angle: Int)
    fun onUpdateLocation(lastLocation: Location?, newLocation: Location)
    fun onGPSFound()
    fun onRideStarted(location: Location)
    fun onMotorcycleStopped(location: Location)
    fun onMotorcycleMoving()
    fun onWorkDone()
}