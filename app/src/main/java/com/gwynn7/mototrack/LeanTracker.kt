package com.gwynn7.mototrack

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sqrt

//Class to handle lean angle

class LeanTracker {
    var isCalibrating = false
    private val calibrationHistory = mutableListOf<DoubleArray>()
    private var startPosition: DoubleArray = DoubleArray(3) { 0.0 }
    private var axisIndex: Int = 0

    fun startCalibration() {
        calibrationHistory.clear()
        isCalibrating = true
    }

    fun stopCalibration() {
        isCalibrating = false

        if (calibrationHistory.size < 2) return
        startPosition = calibrationHistory.first().copyOf() //Set default position offset

        //Foreach axis, get the one with the highest amplitude in movement during the calibration phase, thus becoming the "lean" axis
        val delta = DoubleArray(3) { 0.0 }
        for (i in 1 until calibrationHistory.size) {
            for (j in 0..2) {
                delta[j] += abs(calibrationHistory[i][j] - calibrationHistory[i - 1][j])
            }
        }
        axisIndex = delta.withIndex().maxByOrNull { it.value }?.index ?: 0
    }

    fun getLeanAngleGravity(values: FloatArray): Int {
        var gravity = values.clone()
        val normOfG = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]) //Calculate the norm on the gravity vector

        val degrees = DoubleArray(3)
        for(i in 0..2)
        {
            gravity[i] = gravity[i] / normOfG //Normalize gravity vector
            degrees[i] = Math.toDegrees(acos(gravity[i]).toDouble()) //Get axis inclination angle based on gravity intensity
        }

        if (isCalibrating) {
            calibrationHistory.add(degrees.copyOf())
            return 0
        }

        //Get lean angle considering the offset of the starting position
        return abs((degrees[axisIndex] - startPosition[axisIndex])).roundToInt()
    }
}