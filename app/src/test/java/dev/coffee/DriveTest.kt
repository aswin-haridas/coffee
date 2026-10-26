package dev.coffee

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DriveTest {
    private val lat0 = 9.9312
    private val mPerLon = 111_320 * Math.cos(Math.toRadians(lat0))

    /** 10 min parked, drive east at `speed` for 5 km (1 Hz), 10 min parked. `brakes` inserts hard stops. */
    private fun day(speed: Double, act: Int, brakes: Int = 0): List<Pt> {
        val pts = mutableListOf<Pt>()
        var t = 0L
        var x = 0.0
        repeat(600) { pts += Pt(t, lat0, 76.0, speed = 0f, act = 3); t += 1000 }
        var v = speed
        val brakeAt = (1..brakes).map { it * 5000.0 / (brakes + 1) }.toMutableList()
        while (x < 5000) {
            if (brakeAt.isNotEmpty() && x > brakeAt[0]) { v = 2.0; brakeAt.removeAt(0) } else v = min(speed, v + 1.5)
            x += v
            pts += Pt(t, lat0, 76.0 + x / mPerLon, speed = v.toFloat(), act = act); t += 1000
        }
        repeat(600) { pts += Pt(t, lat0, 76.0 + x / mPerLon, speed = 0f, act = 3); t += 1000 }
        return pts
    }

    private fun min(a: Double, b: Double) = Math.min(a, b)

    @Test fun smoothDrive() {
        val segs = segment(day(15.0, act = 0))
        assertEquals(listOf(Stay::class, Trip::class, Stay::class), segs.map { it::class })
        val s = tripStats((segs[1] as Trip).pts)
        assertEquals(5000.0, s.distM, 150.0)
        assertEquals(Mode.DRIVE, s.mode)
        assertEquals(54.0, s.maxKmh, 1.0)
        assertEquals(Style.CRUISING, s.style)
    }

    @Test fun aggressiveDrive() {
        val s = tripStats((segment(day(20.0, act = 0, brakes = 6))[1] as Trip).pts)
        assertEquals(6, s.harshBrake)
        assertTrue("score ${s.score}", s.style == Style.SPORTY || s.style == Style.AGGRESSIVE)
    }

    @Test fun modeFromSpeedWhenNoActivity() {
        assertEquals(Mode.WALK, tripStats((segment(day(1.4, act = -1))[1] as Trip).pts).mode)
        assertEquals(Mode.CYCLE, tripStats((segment(day(6.0, act = -1))[1] as Trip).pts).mode)
        // activity says "foot" but 15 m/s is impossible on foot → physics wins
        assertEquals(Mode.DRIVE, tripStats((segment(day(15.0, act = 7))[1] as Trip).pts).mode)
    }

    @Test fun jitterIsNotATrip() {
        // ±80 m wander for 20 min, as indoors GPS does
        val pts = (0 until 1200).map { Pt(it * 1000L, lat0 + ((it * 37) % 7 - 3) * 0.00024, 76.0, speed = 0f) }
        assertEquals(1, segment(pts).size)
    }

    @Test fun routinesAndHomeWork() {
        val z = ZoneOffset.UTC
        val mon = ZonedDateTime.of(2026, 10, 5, 0, 0, 0, 0, z)
        val spans = (0 until 14).flatMap { d ->
            val day = mon.plusDays(d.toLong())
            val ms = { h: Int -> day.plusHours(h.toLong()).toInstant().toEpochMilli() }
            if (day.dayOfWeek.value <= 5) listOf(Span("home", ms(0), ms(8)), Span("office", ms(9), ms(17)), Span("home", ms(18), ms(24)))
            else listOf(Span("home", ms(0), ms(24)))
        }
        assertEquals("home" to "office", homeWork(spans, z))
        val work = routines(spans, z).first { it.place == "office" }
        assertEquals("Weekdays", work.days)
        assertEquals(9, work.arrive.hour)
        assertEquals(480, work.avgMin)
    }
}
