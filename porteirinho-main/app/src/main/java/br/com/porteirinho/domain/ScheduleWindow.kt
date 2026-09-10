package br.com.porteirinho.domain

import java.time.DayOfWeek
import java.time.ZonedDateTime

data class ResolvedWindow(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val toleranceEnd: ZonedDateTime,
)

object ScheduleWindow {
    fun resolve(startMinuteOfDay: Int, endMinuteOfDay: Int, toleranceMinutes: Int, now: ZonedDateTime): ResolvedWindow {
        require(startMinuteOfDay in 0..1439)
        require(endMinuteOfDay in 0..1439)
        val crossesMidnight = endMinuteOfDay <= startMinuteOfDay
        val nowMinute = now.hour * 60 + now.minute
        val baseDate = if (crossesMidnight && nowMinute <= endMinuteOfDay + toleranceMinutes) now.toLocalDate().minusDays(1) else now.toLocalDate()
        val start = baseDate.atStartOfDay(now.zone).plusMinutes(startMinuteOfDay.toLong())
        val endDate = if (crossesMidnight) baseDate.plusDays(1) else baseDate
        val end = endDate.atStartOfDay(now.zone).plusMinutes(endMinuteOfDay.toLong())
        return ResolvedWindow(start, end, end.plusMinutes(toleranceMinutes.toLong()))
    }

    fun scheduledDay(startMinuteOfDay: Int, endMinuteOfDay: Int, toleranceMinutes: Int, now: ZonedDateTime): DayOfWeek =
        resolve(startMinuteOfDay, endMinuteOfDay, toleranceMinutes, now).start.dayOfWeek

    fun isVisible(window: ResolvedWindow, now: ZonedDateTime): Boolean =
        !now.isBefore(window.start.minusMinutes(15)) && !now.isAfter(window.toleranceEnd)

    fun isVisible(window: ResolvedWindow, now: ZonedDateTime, startToleranceMinutes: Int, endToleranceMinutes: Int): Boolean =
        !now.isBefore(window.start.minusMinutes(startToleranceMinutes.toLong())) &&
            !now.isAfter(window.end.plusMinutes(endToleranceMinutes.toLong()))

    fun startTiming(window: ResolvedWindow, now: ZonedDateTime, startToleranceMinutes: Int): StartTiming = when {
        now.isBefore(window.start.minusMinutes(startToleranceMinutes.toLong())) -> StartTiming.TOO_EARLY
        now.isAfter(window.start.plusMinutes(startToleranceMinutes.toLong())) -> StartTiming.LATE
        else -> StartTiming.ON_TIME
    }
}

enum class StartTiming { TOO_EARLY, ON_TIME, LATE }
