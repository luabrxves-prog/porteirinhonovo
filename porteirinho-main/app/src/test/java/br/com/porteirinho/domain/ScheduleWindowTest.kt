package br.com.porteirinho.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleWindowTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    @Test
    fun `resolves patrol that crosses midnight`() {
        val now = ZonedDateTime.of(2026, 9, 10, 0, 10, 0, 0, zone)
        val window = ScheduleWindow.resolve(23 * 60 + 30, 30, 10, now)

        assertEquals(9, window.start.dayOfMonth)
        assertEquals(23, window.start.hour)
        assertEquals(10, window.end.dayOfMonth)
        assertEquals(0, window.end.hour)
        assertTrue(ScheduleWindow.isVisible(window, now))
    }
}
