package br.com.porteirinho.domain

import java.time.DayOfWeek
import java.time.ZonedDateTime

data class ResolvedWindow(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val toleranceEnd: ZonedDateTime,
)

object ScheduleWindow {
    fun resolve(
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        toleranceMinutes: Int,
        now: ZonedDateTime,
    ): ResolvedWindow {
        require(startMinuteOfDay in 0..1439)
        require(endMinuteOfDay in 0..1439)

        val crossesMidnight = endMinuteOfDay <= startMinuteOfDay
        val nowMinute = now.hour * 60 + now.minute
        val baseDate = if (crossesMidnight && nowMinute <= endMinuteOfDay + toleranceMinutes) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }
        val start = baseDate.atStartOfDay(now.zone).plusMinutes(startMinuteOfDay.toLong())
        val endDate = if (crossesMidnight) baseDate.plusDays(1) else baseDate
        val end = endDate.atStartOfDay(now.zone).plusMinutes(endMinuteOfDay.toLong())
        return ResolvedWindow(start, end, end.plusMinutes(toleranceMinutes.toLong()))
    }

    fun scheduledDay(
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        toleranceMinutes: Int,
        now: ZonedDateTime,
    ): DayOfWeek = resolve(startMinuteOfDay, endMinuteOfDay, toleranceMinutes, now).start.dayOfWeek

    fun isVisible(window: ResolvedWindow, now: ZonedDateTime): Boolean =
        !now.isBefore(window.start.minusMinutes(15)) && !now.isAfter(window.toleranceEnd)
}
