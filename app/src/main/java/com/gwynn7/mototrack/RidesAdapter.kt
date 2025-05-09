package com.gwynn7.mototrack

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.gwynn7.mototrack.database.Ride
import com.gwynn7.mototrack.mainActivityFragments.ListFragmentDirections
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class RidesAdapter(private val rides: List<Ride>) :
    RecyclerView.Adapter<RidesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val distanceText: TextView = view.findViewById(R.id.distanceText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val dateText: TextView = view.findViewById(R.id.dateText)
        val cardView: CardView = view.findViewById<CardView>(R.id.rideCard)
        val trackImage: ImageView = view.findViewById<ShapeableImageView>(R.id.trackImage)
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(viewGroup.context)
            .inflate(R.layout.list_element, viewGroup, false)

        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val file = File(rides[position].snapshot ?: "")
        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            viewHolder.trackImage.setImageBitmap(bitmap)
        }

        viewHolder.distanceText.text = "%.2f km".format(rides[position].totalDistance / 1000.0)

        val date = Date(rides[position].startTime)
        val format = SimpleDateFormat("EEEE dd MMM yyyy", Locale.getDefault())

        viewHolder.timeText.text = getRideDuration(rides[position].startTime, rides[position].endTme)
        viewHolder.dateText.text = format.format(date).replaceFirstChar{i -> i.uppercase()}

        viewHolder.cardView.setOnClickListener {
            val action = ListFragmentDirections.actionListToRide(rides[position].id)
            viewHolder.itemView.findNavController().navigate(action)
        }
    }

    override fun getItemCount() = rides.size

    fun getRideDuration(start: Long, end: Long): String
    {
        val duration = end - start
        val totalSeconds = duration / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

}