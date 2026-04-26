package com.example.whattodo

import android.content.Context
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

data class HolidayInfo(
    val label: String,
    val tone: HolidayTone,
)

enum class HolidayTone {
    OFFICIAL,
    SUBSTITUTE,
    WEEKEND,
}

object HolidayCalendarStore {
    private const val ASSET_NAME = "taiwan_holidays.json"
    private val cache = AtomicReference<Map<Int, Map<String, HolidayInfo>>?>()

    fun infoForDay(context: Context, millis: Long): HolidayInfo? {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val holiday = load(context)[year]?.get("$month-$day")
        if (holiday != null) return holiday
        return if (isWeekend(millis)) {
            HolidayInfo("週末", HolidayTone.WEEKEND)
        } else {
            null
        }
    }

    fun countOfficialHolidayDays(context: Context, monthStart: Long): Int {
        val calendar = Calendar.getInstance().apply { timeInMillis = monthStart }
        val days = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        var count = 0
        for (day in 1..days) {
            if (infoForDay(context, dayStartForMonth(monthStart, day))?.tone == HolidayTone.OFFICIAL) {
                count += 1
            }
        }
        return count
    }

    private fun load(context: Context): Map<Int, Map<String, HolidayInfo>> {
        cache.get()?.let { return it }
        val parsed = runCatching {
            val raw = context.applicationContext.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            parseCalendarJson(JSONObject(raw))
        }.getOrElse {
            emptyMap()
        }
        cache.compareAndSet(null, parsed)
        return cache.get().orEmpty()
    }

    private fun parseCalendarJson(json: JSONObject): Map<Int, Map<String, HolidayInfo>> {
        val yearsObject = json.optJSONObject("years") ?: JSONObject()
        val fixedMap = parseHolidayMap(json.optJSONObject("fixed") ?: JSONObject())
        val result = linkedMapOf<Int, Map<String, HolidayInfo>>()

        yearsObject.keys().forEach { yearKey ->
            val year = yearKey.toIntOrNull() ?: return@forEach
            val yearMap = parseHolidayMap(yearsObject.optJSONObject(yearKey) ?: JSONObject())
            result[year] = (fixedMap + yearMap)
        }

        return result
    }

    private fun parseHolidayMap(jsonObject: JSONObject): Map<String, HolidayInfo> {
        val result = linkedMapOf<String, HolidayInfo>()
        jsonObject.keys().forEach { dateKey ->
            val entry = jsonObject.opt(dateKey) ?: return@forEach
            val info = when (entry) {
                is JSONObject -> HolidayInfo(
                    label = entry.optString("label").ifBlank { dateKey },
                    tone = entry.optString("tone", HolidayTone.OFFICIAL.name)
                        .toHolidayTone(),
                )
                is String -> HolidayInfo(entry, HolidayTone.OFFICIAL)
                else -> null
            }
            if (info != null) {
                result[dateKey] = info
            }
        }
        return result
    }

    private fun String.toHolidayTone(): HolidayTone {
        return runCatching { HolidayTone.valueOf(uppercase(Locale.getDefault())) }
            .getOrDefault(HolidayTone.OFFICIAL)
    }

    private fun dayStartForMonth(monthStart: Long, dayOfMonth: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = monthStart
            set(Calendar.DAY_OF_MONTH, dayOfMonth)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun isWeekend(millis: Long): Boolean {
        val dayOfWeek = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)
        return dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
    }
}
