package com.example.whattodo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

sealed class TaskRepeatRule {
    data object None : TaskRepeatRule()

    data object Daily : TaskRepeatRule()

    data class Weekly(
        val daysOfWeek: Set<Int>,
    ) : TaskRepeatRule()

    data class MonthlyWeekday(
        val ordinal: Int,
        val dayOfWeek: Int,
    ) : TaskRepeatRule()

    data class MonthlyDate(
        val dayOfMonth: Int,
    ) : TaskRepeatRule()

    data object MonthlyLastWorkday : TaskRepeatRule()

    fun toStorage(): RepeatStorage {
        return when (this) {
            None -> RepeatStorage(TYPE_NONE, "{}")
            Daily -> RepeatStorage(TYPE_DAILY, "{}")
            is Weekly -> RepeatStorage(
                TYPE_WEEKLY,
                JSONObject().apply {
                    put("daysOfWeek", JSONArray(daysOfWeek.toList()))
                }.toString(),
            )
            is MonthlyWeekday -> RepeatStorage(
                TYPE_MONTHLY_WEEKDAY,
                JSONObject().apply {
                    put("ordinal", ordinal)
                    put("dayOfWeek", dayOfWeek)
                }.toString(),
            )
            is MonthlyDate -> RepeatStorage(
                TYPE_MONTHLY_DATE,
                JSONObject().apply {
                    put("dayOfMonth", dayOfMonth)
                }.toString(),
            )
            MonthlyLastWorkday -> RepeatStorage(TYPE_MONTHLY_LAST_WORKDAY, "{}")
        }
    }

    fun summary(context: Context): String {
        return when (this) {
            None -> context.getString(R.string.repeat_none)
            Daily -> context.getString(R.string.repeat_daily_format)
            is Weekly -> {
                val days = daysOfWeek.sorted().joinToString("、") { dayOfWeekLabel(context, it) }
                context.getString(R.string.repeat_weekly_format, days)
            }
            is MonthlyWeekday -> {
                val ordinalLabel = ordinalLabel(context, ordinal)
                context.getString(
                    R.string.repeat_monthly_weekday_format,
                    ordinalLabel,
                    dayOfWeekLabel(context, dayOfWeek),
                )
            }
            is MonthlyDate -> context.getString(R.string.repeat_monthly_date_format, dayOfMonth)
            MonthlyLastWorkday -> context.getString(R.string.repeat_monthly_last_workday_format)
        }
    }

    companion object {
        const val TYPE_NONE = 0
        const val TYPE_DAILY = 1
        const val TYPE_WEEKLY = 2
        const val TYPE_MONTHLY_WEEKDAY = 3
        const val TYPE_MONTHLY_DATE = 4
        const val TYPE_MONTHLY_LAST_WORKDAY = 5

        fun fromStorage(type: Int, json: String): TaskRepeatRule {
            return when (type) {
                TYPE_DAILY -> {
                    val objectJson = JSONObject(json)
                    if (objectJson.has("daysOfWeek")) {
                        val array = objectJson.optJSONArray("daysOfWeek") ?: JSONArray()
                        val days = buildSet {
                            for (index in 0 until array.length()) {
                                add(array.optInt(index, Calendar.SATURDAY))
                            }
                        }
                        Weekly(days.ifEmpty { setOf(Calendar.SATURDAY) })
                    } else {
                        Daily
                    }
                }
                TYPE_WEEKLY -> {
                    val objectJson = JSONObject(json)
                    if (objectJson.has("ordinal") || objectJson.has("dayOfWeek")) {
                        MonthlyWeekday(
                            ordinal = objectJson.optInt("ordinal", 1),
                            dayOfWeek = objectJson.optInt("dayOfWeek", Calendar.FRIDAY),
                        )
                    } else {
                        val array = objectJson.optJSONArray("daysOfWeek") ?: JSONArray()
                        val days = buildSet {
                            for (index in 0 until array.length()) {
                                add(array.optInt(index, Calendar.SATURDAY))
                            }
                        }
                        Weekly(days.ifEmpty { setOf(Calendar.SATURDAY) })
                    }
                }
                TYPE_MONTHLY_WEEKDAY -> {
                    val objectJson = JSONObject(json)
                    MonthlyWeekday(
                        ordinal = objectJson.optInt("ordinal", 1),
                        dayOfWeek = objectJson.optInt("dayOfWeek", Calendar.FRIDAY),
                    )
                }
                TYPE_MONTHLY_DATE -> {
                    val objectJson = JSONObject(json)
                    MonthlyDate(
                        dayOfMonth = objectJson.optInt("dayOfMonth", 1).coerceIn(1, 31),
                    )
                }
                TYPE_MONTHLY_LAST_WORKDAY -> MonthlyLastWorkday
                else -> None
            }
        }

        fun nextDueAfter(
            rule: TaskRepeatRule,
            anchorMillis: Long,
            timeZone: TimeZone = TimeZone.getTimeZone("UTC"),
        ): Long? {
            val anchor = startOfDay(anchorMillis, timeZone)
            return when (rule) {
                None -> null
                Daily -> addDays(anchor, 1, timeZone)
                is Weekly -> nextWeekly(rule, anchor, timeZone)
                is MonthlyWeekday -> nextMonthlyWeekday(rule, anchor, timeZone)
                is MonthlyDate -> nextMonthlyDate(rule, anchor, timeZone)
                MonthlyLastWorkday -> nextMonthlyLastWorkday(anchor, timeZone)
            }
        }

        private fun nextWeekly(rule: Weekly, anchorMillis: Long, timeZone: TimeZone): Long? {
            if (rule.daysOfWeek.isEmpty()) return null
            val calendar = calendarFor(anchorMillis, timeZone)
            repeat(14) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                if (rule.daysOfWeek.contains(calendar.get(Calendar.DAY_OF_WEEK))) {
                    return startOfDay(calendar.timeInMillis, timeZone)
                }
            }
            return null
        }

        private fun nextMonthlyWeekday(rule: MonthlyWeekday, anchorMillis: Long, timeZone: TimeZone): Long? {
            val start = calendarFor(anchorMillis, timeZone)
            repeat(24) { monthOffset ->
                val candidate = calendarFor(start.timeInMillis, timeZone).apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    add(Calendar.MONTH, monthOffset)
                }
                val occurrence = monthlyWeekdayOccurrence(
                    year = candidate.get(Calendar.YEAR),
                    month = candidate.get(Calendar.MONTH),
                    ordinal = rule.ordinal,
                    dayOfWeek = rule.dayOfWeek,
                    timeZone = timeZone,
                )
                if (occurrence != null && occurrence > anchorMillis) {
                    return occurrence
                }
            }
            return null
        }

        private fun nextMonthlyDate(rule: MonthlyDate, anchorMillis: Long, timeZone: TimeZone): Long? {
            val start = calendarFor(anchorMillis, timeZone)
            repeat(24) { monthOffset ->
                val candidate = calendarFor(start.timeInMillis, timeZone).apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    add(Calendar.MONTH, monthOffset)
                }
                val occurrence = monthlyDateOccurrence(
                    year = candidate.get(Calendar.YEAR),
                    month = candidate.get(Calendar.MONTH),
                    dayOfMonth = rule.dayOfMonth,
                    timeZone = timeZone,
                )
                if (occurrence != null && occurrence > anchorMillis) {
                    return occurrence
                }
            }
            return null
        }

        private fun nextMonthlyLastWorkday(anchorMillis: Long, timeZone: TimeZone): Long? {
            val start = calendarFor(anchorMillis, timeZone)
            repeat(24) { monthOffset ->
                val candidate = calendarFor(start.timeInMillis, timeZone).apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    add(Calendar.MONTH, monthOffset)
                }
                val occurrence = monthlyLastWorkdayOccurrence(
                    year = candidate.get(Calendar.YEAR),
                    month = candidate.get(Calendar.MONTH),
                    timeZone = timeZone,
                )
                if (occurrence != null && occurrence > anchorMillis) {
                    return occurrence
                }
            }
            return null
        }

        private fun monthlyWeekdayOccurrence(
            year: Int,
            month: Int,
            ordinal: Int,
            dayOfWeek: Int,
            timeZone: TimeZone,
        ): Long? {
            val calendar = Calendar.getInstance(timeZone).apply {
                clear()
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, 1)
            }

            return if (ordinal == -1) {
                calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
                while (calendar.get(Calendar.DAY_OF_WEEK) != dayOfWeek) {
                    calendar.add(Calendar.DAY_OF_MONTH, -1)
                }
                startOfDay(calendar.timeInMillis, timeZone)
            } else {
                var matched = 0
                while (calendar.get(Calendar.MONTH) == month) {
                    if (calendar.get(Calendar.DAY_OF_WEEK) == dayOfWeek) {
                        matched += 1
                        if (matched == ordinal) {
                            return startOfDay(calendar.timeInMillis, timeZone)
                        }
                    }
                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                }
                null
            }
        }

        private fun monthlyDateOccurrence(
            year: Int,
            month: Int,
            dayOfMonth: Int,
            timeZone: TimeZone,
        ): Long? {
            val calendar = Calendar.getInstance(timeZone).apply {
                clear()
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
            }
            val maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth.coerceAtMost(maxDay))
            return startOfDay(calendar.timeInMillis, timeZone)
        }

        private fun monthlyLastWorkdayOccurrence(
            year: Int,
            month: Int,
            timeZone: TimeZone,
        ): Long? {
            val calendar = Calendar.getInstance(timeZone).apply {
                clear()
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            }
            while (!isWorkday(calendar.get(Calendar.DAY_OF_WEEK))) {
                calendar.add(Calendar.DAY_OF_MONTH, -1)
            }
            return startOfDay(calendar.timeInMillis, timeZone)
        }

        private fun addDays(anchorMillis: Long, days: Int, timeZone: TimeZone): Long {
            return calendarFor(anchorMillis, timeZone).apply {
                add(Calendar.DAY_OF_YEAR, days)
            }.let { startOfDay(it.timeInMillis, timeZone) }
        }

        private fun ordinalLabel(context: Context, ordinal: Int): String {
            return when (ordinal) {
                1 -> context.getString(R.string.repeat_monthly_first)
                2 -> context.getString(R.string.repeat_monthly_second)
                3 -> context.getString(R.string.repeat_monthly_third)
                4 -> context.getString(R.string.repeat_monthly_fourth)
                -1 -> context.getString(R.string.repeat_monthly_last)
                else -> ordinal.toString()
            }
        }

        private fun startOfDay(millis: Long, timeZone: TimeZone): Long {
            return calendarFor(millis, timeZone).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        private fun calendarFor(millis: Long, timeZone: TimeZone): Calendar {
            return Calendar.getInstance(timeZone).apply {
                timeInMillis = millis
            }
        }

        private fun isWorkday(dayOfWeek: Int): Boolean {
            return dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY
        }

        private fun dayOfWeekLabel(context: Context, dayOfWeek: Int): String {
            return when (dayOfWeek) {
                Calendar.SUNDAY -> context.getString(R.string.day_sunday)
                Calendar.MONDAY -> context.getString(R.string.day_monday)
                Calendar.TUESDAY -> context.getString(R.string.day_tuesday)
                Calendar.WEDNESDAY -> context.getString(R.string.day_wednesday)
                Calendar.THURSDAY -> context.getString(R.string.day_thursday)
                Calendar.FRIDAY -> context.getString(R.string.day_friday)
                Calendar.SATURDAY -> context.getString(R.string.day_saturday)
                else -> dayOfWeek.toString()
            }
        }
    }
}

data class RepeatStorage(
    val type: Int,
    val json: String,
)
