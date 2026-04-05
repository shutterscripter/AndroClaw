package com.androclaw.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.androclaw.AndroClawApplication
import com.androclaw.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun execute(input: Map<String, Any>): String {
        val message = input["message"] as? String ?: return "Missing reminder message"
        val triggerTime = input["trigger_time"] as? String ?: return "Missing trigger_time"

        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val triggerMillis = format.parse(triggerTime)?.time
                ?: return "Invalid time format. Use ISO format: YYYY-MM-DDTHH:MM:SS"

            if (triggerMillis < System.currentTimeMillis()) {
                return "The specified time is in the past. Please provide a future time."
            }

            val requestCode = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra("message", message)
                putExtra("request_code", requestCode)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // Fall back to inexact alarm
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            }

            val displayFormat = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
            val displayTime = displayFormat.format(format.parse(triggerTime)!!)
            "Reminder set for $displayTime: \"$message\""
        } catch (e: Exception) {
            "Failed to set reminder: ${e.message}"
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("message") ?: "Reminder"
        val requestCode = intent.getIntExtra("request_code", 0)

        val notification = NotificationCompat.Builder(context, AndroClawApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AndroClaw Reminder")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
        } catch (e: SecurityException) {
            Toast.makeText(context, "Reminder: $message", Toast.LENGTH_LONG).show()
        }
    }
}
