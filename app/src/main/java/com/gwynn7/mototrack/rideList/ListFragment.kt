package com.gwynn7.mototrack.rideList

import android.content.Intent
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.gwynn7.mototrack.R
import com.gwynn7.mototrack.TrackActivity
import com.gwynn7.mototrack.database.RideDAO
import com.gwynn7.mototrack.database.RideDatabase
import com.gwynn7.mototrack.databinding.ListFragmentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ListFragment : Fragment(), MenuProvider {

    private var _binding: ListFragmentBinding? = null
    private val binding get() = _binding!!

    private lateinit var rideDao: RideDAO

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ListFragmentBinding.inflate(inflater, container, false)

        //Load database
        val db = RideDatabase.getInstance(requireContext())
        rideDao = db.rideDao()

        //Start tracking activity on floating action button click
        binding.fab.setOnClickListener { view ->
            val intent = Intent(activity, TrackActivity::class.java)
            startActivity(intent)
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //Allow fragment to contribute to the menu
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()

        //Get RecyclerView and set layout type
        val list = binding.ridesList
        list.layoutManager = LinearLayoutManager(requireContext())

        //Launch a coroutine to load the rides from the database
        lifecycleScope.launch {
            //Get rides from database on a different thread
            val rides = withContext(Dispatchers.IO) {
                rideDao.getRides()
            }
            //Return to main thread to set the adapter and views
            if(rides.isEmpty()) binding.placeholder.visibility = View.VISIBLE
            //Set adapter for the RecyclerView, passing rideData
            val adapter = RidesAdapter(rides.reversed(), viewLifecycleOwner)
            list.adapter = adapter
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.settings_menu, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.action_settings ->
            {
                //Navigate to settings fragment
                val action = ListFragmentDirections.actionListToSettings()
                findNavController().navigate(action)
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