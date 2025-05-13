package com.gwynn7.mototrack.rideList

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.lifecycleScope
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.gwynn7.mototrack.R
import com.gwynn7.mototrack.database.Ride
import com.gwynn7.mototrack.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RidesAdapter(private val rides: List<Ride>, private val lifecycleOwner: LifecycleOwner) : RecyclerView.Adapter<RidesAdapter.ViewHolder>() {

    //Declare the views on RecyclerView's elements
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val distanceText: TextView = view.findViewById(R.id.distanceText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val dateText: TextView = view.findViewById(R.id.dateText)
        val cardView: CardView = view.findViewById<CardView>(R.id.rideCard)
        val trackImage: ImageView = view.findViewById<ShapeableImageView>(R.id.trackImage)
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        //Get views reference
        val view = LayoutInflater.from(viewGroup.context).inflate(R.layout.list_element, viewGroup, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        //Launch a coroutine to avoid loading the image on the main thread
        lifecycleOwner.lifecycleScope.launch {
            val file = File(rides[position].snapshot ?: "")
            if (file.exists()) {
                //Load the image on a different thread
                val bitmap: Bitmap = withContext(Dispatchers.IO){
                    BitmapFactory.decodeFile(file.absolutePath)
                }
                //Return to main thread to set the image
                viewHolder.trackImage.setImageBitmap(bitmap)
            }
        }

        //Set views of RecyclerView's element

        viewHolder.distanceText.text = "%.2f km".format(rides[position].totalDistance / 1000.0)

        val date = Date(rides[position].startTime)
        val format = SimpleDateFormat("EEEE dd MMM yyyy", Locale.getDefault())

        viewHolder.timeText.text = formatTime(rides[position].totalSeconds)
        viewHolder.dateText.text = format.format(date).replaceFirstChar{i -> i.uppercase()}

        viewHolder.cardView.setOnClickListener {
            //Navigate to ride fragment
            val action = ListFragmentDirections.actionListToRide(rides[position].id)
            viewHolder.itemView.findNavController().navigate(action)
        }
    }

    override fun getItemCount() = rides.size
}