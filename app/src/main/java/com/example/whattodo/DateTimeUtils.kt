package com.example.whattodo

import java.util.Calendar
import java.util.TimeZone

internal const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

fun hasExplicitTime(millis: Long?): Boolean {
    if (millis == null) return false
    return millis % MILLIS_PER_DAY != 0L
}

fun normalizeDateOnlyMillis(millis: Long): Long {
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

fun combineDateAndTime(dateMillis: Long, hourOfDay: Int, minute: Int): Long {
    val utcDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = dateMillis
    }

    return Calendar.getInstance().apply {
        set(Calendar.YEAR, utcDate.get(Calendar.YEAR))
        set(Calendar.MONTH, utcDate.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utcDate.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, hourOfDay)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

fun extractTimeParts(millis: Long): Pair<Int, Int> {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    return calendar.get(Calendar.HOUR_OF_DAY) to calendar.get(Calendar.MINUTE)
}
