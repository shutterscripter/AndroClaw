package com.androclaw.tools

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.androclaw.utils.PermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper
) {

    suspend fun execute(input: Map<String, Any>): String {
        val permError = permissionHelper.ensurePermissionsForTool(context, "create_calendar_event")
        if (permError != null) return permError

        val title = input["title"] as? String ?: return "Missing event title"
        val date = input["date"] as? String ?: return "Missing event date"
        val time = input["time"] as? String ?: return "Missing event time"
        val durationMinutes = when (val d = input["duration_minutes"]) {
            is Number -> d.toInt()
            is String -> d.toIntOrNull() ?: 60
            else -> 60
        }

        return try {
            val dateTime = "$date $time"
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            format.timeZone = TimeZone.getDefault()
            val startMillis = format.parse(dateTime)?.time
                ?: return "Invalid date/time format. Use YYYY-MM-DD for date and HH:MM for time."
            val endMillis = startMillis + durationMinutes * 60 * 1000L

            // Get primary calendar ID
            val calendarId = getPrimaryCalendarId()
                ?: return "No calendar found on this device."

            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val eventId = uri.lastPathSegment
                "Calendar event created successfully!\nTitle: $title\nDate: $date at $time\nDuration: $durationMinutes minutes\nEvent ID: $eventId"
            } else {
                "Failed to create calendar event."
            }
        } catch (e: Exception) {
            "Failed to create calendar event: ${e.message}"
        }
    }

    private fun getPrimaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )

        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null, null, null
        ) ?: return null

        cursor.use {
            // Try to find primary calendar first
            while (it.moveToNext()) {
                val isPrimary = it.getInt(1)
                if (isPrimary == 1) {
                    return it.getLong(0)
                }
            }
            // Fall back to first available calendar
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        return null
    }
}
