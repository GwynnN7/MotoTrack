package com.gwynn7.mototrack

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sqrt

class LeanTracker {
    var isCalibrating = false
    private val calibrationHistory = mutableListOf<DoubleArray>()
    private var startPosition: DoubleArray = DoubleArray(3)
    private var axisIndex: Int = 0

    fun startCalibration() {
        isCalibrating = true
    }

    fun stopCalibration() {
        if (calibrationHistory.size < 2) return
        startPosition = calibrationHistory.first().copyOf()

        val delta = DoubleArray(3) { 0.0 }
        for (i in 1 until calibrationHistory.size) {
            for (j in 0..2) {
                delta[j] += abs(calibrationHistory[i][j] - calibrationHistory[i - 1][j])
            }
        }

        axisIndex = delta.withIndex().maxByOrNull { it.value }?.index ?: 0

        isCalibrating = false
        calibrationHistory.clear()
    }

    fun getLeanAngleGravity(values: FloatArray): Int {
        var gravity = values.clone();
        val normOfG = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]);

        val degrees = DoubleArray(3)
        for(i in 0..2)
        {
            gravity[i] = gravity[i] / normOfG
            degrees[i] = Math.toDegrees(acos(gravity[i]).toDouble())
        }

        if (isCalibrating) {
            calibrationHistory.add(degrees.copyOf())
            return 0
        }

        return abs((degrees[axisIndex] - startPosition[axisIndex])).roundToInt()
    }
}