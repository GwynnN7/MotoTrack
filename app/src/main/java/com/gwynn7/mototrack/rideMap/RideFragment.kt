package com.gwynn7.mototrack.rideMap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.gwynn7.mototrack.R
import com.gwynn7.mototrack.database.Ride
import com.gwynn7.mototrack.database.RideDAO
import com.gwynn7.mototrack.database.RideData
import com.gwynn7.mototrack.database.RideDatabase
import com.gwynn7.mototrack.databinding.RideFragmentBinding
import com.gwynn7.mototrack.getStatsName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RideFragment : Fragment(), MenuProvider {
    private var _binding: RideFragmentBinding? = null
    private val binding get() = _binding!!

    private lateinit var rideDao: RideDAO
    private var ride: Ride? = null
    private var rideData: List<RideData> = listOf()
    private val rideDataViewModel: RideDataViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = RideFragmentBinding.inflate(inflater, container, false)

        //Load database
        val db = RideDatabase.getInstance(requireContext())
        rideDao = db.rideDao()

        //Set loading screen
        binding.tabsLayout.visibility = View.GONE
        binding.loadingLayout.visibility = View.VISIBLE

        val args: RideFragmentArgs by navArgs()
        //Launch a coroutine to load the rides from the database
        lifecycleScope.launch {
            //Get ride data and rides from database on a different thread
            withContext(Dispatchers.IO) {
                ride = rideDao.getRideById(args.rideId)
                rideData = rideDao.getRideDataByRideId(args.rideId)
            }
            //Return to main thread to use the loaded data on the UI

            rideDataViewModel.rideData = rideData //Update the data on the ViewModel

            if(ride == null) findNavController().navigateUp()
            else
            {
                //Setup adapter for ViewPager
                val mapTypeAdapter = MapTypeAdapter(requireActivity())
                val viewPager: ViewPager2 = binding.mapTypePager
                viewPager.adapter = mapTypeAdapter
                viewPager.isUserInputEnabled = false

                //Setup tabs for ViewPager
                val tabs: TabLayout = binding.mapTypeTab
                TabLayoutMediator(tabs, viewPager) { tab, position -> tab.text = resources.getString(getStatsName(position)) }.attach()

                //Allow fragment to contribute to the menu
                requireActivity().addMenuProvider(this@RideFragment, viewLifecycleOwner, Lifecycle.State.RESUMED)

                //Remove loading screen
                binding.tabsLayout.visibility = View.VISIBLE
                binding.loadingLayout.visibility = View.GONE
            }
        }

        return binding.root
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.delete_menu, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.action_delete ->
            {
                if(ride != null) {
                    //Launch a coroutine to delete ride from the database
                    lifecycleScope.launch {
                        //Delete rides and image on a different thread
                        withContext(Dispatchers.IO) {
                            rideDao.deleteRide(ride!!.id)
                            rideDao.deleteRideData(ride!!.id)

                            val filename = ride!!.snapshot
                            if(filename != null){
                                val file = File(requireContext().filesDir, filename)
                                if(file.exists()) file.delete()
                            }
                        }
                        findNavController().navigateUp()
                    }
                }
                true
            }
            else -> false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}