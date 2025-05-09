package com.gwynn7.mototrack.mainActivityFragments

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.gwynn7.mototrack.database.Ride
import com.gwynn7.mototrack.database.RideData

class MapTypeAdapter(fragmentActivity: FragmentActivity, private val ride: Ride, private val rideData: List<RideData>) : FragmentStateAdapter(fragmentActivity) {
    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return RideMapFragment(position, ride, rideData)
    }
}