package com.gwynn7.mototrack

import kotlin.math.*

class LeanTracker {
    var isCalibrating = false

    private var gravityVector: Vector3 = Vector3.empty()
    private var forwardVector: Vector3 = Vector3.empty()
    private val forwardVectors = mutableListOf<Vector3>()
    private val gravityVectors = mutableListOf<Vector3>()

    fun startCalibration() {
        isCalibrating = true
    }

    fun resetCalibration() {
        forwardVectors.clear()
        gravityVectors.clear()
        isCalibrating = false
    }

    fun stopCalibration() {
        isCalibrating = false

        //Get the max amplitude normalized vector for the forward direction
        forwardVector = if(forwardVectors.isNotEmpty()) forwardVectors.maxBy { vector -> vector.magnitude() }.normalize() else forwardVector

        //Get the average of the gravity vectors for the default position
        for(vector in gravityVectors)
        {
            gravityVector += vector
        }
        gravityVector /= gravityVectors.size.toFloat()

        //Project gravity vector to the lean plane (orthogonal to the forward vector)
        gravityVector = gravityVector.normalize().project(forwardVector).normalize()
    }

    fun addAcceleration(vector: FloatArray) {
        if(!isCalibrating) return
        forwardVectors.add(Vector3.fromArray(vector))
    }

    fun addGravity(vector: FloatArray) {
        if(!isCalibrating) return
        gravityVectors.add(Vector3.fromArray(vector))
    }

    fun calculateLeanAngle(gravity: FloatArray): Int {
        if(isCalibrating) return 0

        val leanVector = Vector3.fromArray(gravity).normalize()
        //Project lean vector to the lean plane (orthogonal to the forward vector)
        val leanProjection = leanVector.project(forwardVector).normalize()
        //Calculate dot product between gravity vector and lean vector
        val dotProduct = leanProjection.dot(gravityVector).coerceIn(-1f, 1f)
        //Calculate the angle from the dot product
        return Math.toDegrees(acos(dotProduct).toDouble()).roundToInt()
    }
}