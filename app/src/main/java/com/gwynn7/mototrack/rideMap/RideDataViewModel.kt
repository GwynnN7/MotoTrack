package com.gwynn7.mototrack.rideMap

import androidx.lifecycle.ViewModel
import com.gwynn7.mototrack.database.RideData

//ViewModel to share rides data between fragments of statistics tabs

class RideDataViewModel : ViewModel(){
    var rideData: List<RideData> = emptyList()
}