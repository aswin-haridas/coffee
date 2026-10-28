package dev.coffee

import org.junit.Assert.*
import org.junit.Test

class GoogleTimelineTest {
    @Test fun parsesOnDeviceAndTakeout() {
        val r = GoogleTimeline.parse(javaClass.getResource("/timeline.json")!!.readText())
        // nested visits and 40 m strolls are left out
        assertEquals(listOf("spot/home", "google/ChIJcafe", "google/ChIJold"), r.visits.map { it.place })
        assertEquals(1714530600000L, r.visits[0].arrived) // 08:00 IST
        assertEquals(10.0271, r.visits[0].lat, 1e-9)
        assertEquals("Blue Tokai", r.visits[2].name)
        assertEquals(listOf(Mode.DRIVE, Mode.WALK), r.trips.map { it.mode })
        assertEquals(18.0, r.trips[0].avg, 0.01) // 9 km in 30 min
        assertNull(r.trips[0].style)
        assertEquals(2, r.path.size)
        assertEquals(76.32, r.path[1][1], 1e-9)
    }
}
