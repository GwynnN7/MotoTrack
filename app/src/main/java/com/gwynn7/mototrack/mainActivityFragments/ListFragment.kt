package com.gwynn7.mototrack.mainActivityFragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gwynn7.mototrack.R
import com.gwynn7.mototrack.RidesAdapter
import com.gwynn7.mototrack.TrackActivity
import com.gwynn7.mototrack.database.RideDAO
import com.gwynn7.mototrack.database.RideDatabase
import com.gwynn7.mototrack.databinding.ListFragmentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ListFragment : Fragment() {

    private var _binding: ListFragmentBinding? = null
    private val binding get() = _binding!!

    private lateinit var rideDao: RideDAO

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ListFragmentBinding.inflate(inflater, container, false)

        val db = RideDatabase.getInstance(requireContext())
        rideDao = db.rideDao()

        binding.fab.setOnClickListener { view ->
            val intent = Intent(activity, TrackActivity::class.java)
            startActivity(intent)
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val list = view.findViewById<RecyclerView>(R.id.ridesList)
        list.layoutManager = LinearLayoutManager(requireContext())
        lifecycleScope.launch {
            val rides = withContext(Dispatchers.IO){
                rideDao.getRides()
            }
            val adapter = RidesAdapter(rides.reversed())
            list.adapter = adapter
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}