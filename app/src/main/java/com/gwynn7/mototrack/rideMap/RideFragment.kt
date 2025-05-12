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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val statsNames = arrayOf(
    R.string.lean_title,
    R.string.speed_title,
    R.string.acceleration_title,
    R.string.altitude_title
)

class RideFragment : Fragment(), MenuProvider {
    private var _binding: RideFragmentBinding? = null
    private val binding get() = _binding!!

    private lateinit var rideDao: RideDAO
    private var ride: Ride? = null
    private var rideData: List<RideData> = listOf()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = RideFragmentBinding.inflate(inflater, container, false)

        val db = RideDatabase.getInstance(requireContext())
        rideDao = db.rideDao()

        binding.tabsLayout.visibility = View.GONE
        binding.loadingLayout.visibility = View.VISIBLE

        val args: RideFragmentArgs by navArgs()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ride = rideDao.getRideById(args.rideId)
                rideData = rideDao.getRideDataByRideId(args.rideId)
            }

            if(ride == null) findNavController().navigateUp()
            else
            {
                val mapTypeAdapter = MapTypeAdapter(requireActivity(), rideData)
                val viewPager: ViewPager2 = binding.mapTypePager
                viewPager.adapter = mapTypeAdapter
                viewPager.isUserInputEnabled = false

                val tabs: TabLayout = binding.mapTypeTab
                TabLayoutMediator(tabs, viewPager) { tab, position -> tab.text = resources.getString(statsNames[position]) }.attach()

                requireActivity().addMenuProvider(this@RideFragment, viewLifecycleOwner, Lifecycle.State.RESUMED)

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
                    lifecycleScope.launch {
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}