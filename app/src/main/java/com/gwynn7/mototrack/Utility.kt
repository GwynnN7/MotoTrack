package com.gwynn7.mototrack

import android.app.Notification
import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import java.util.Locale

fun formatTime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours.toInt(), minutes.toInt(), secs.toInt())
}

fun createNotification(context: Context, resources: Resources, title: String, text: String): NotificationCompat.Builder {
    return NotificationCompat.Builder(context, "motoTrack")
        .setSmallIcon(R.drawable.logo)
        .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.logo))
        .setContentTitle(title)
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setDefaults(Notification.DEFAULT_ALL)
}