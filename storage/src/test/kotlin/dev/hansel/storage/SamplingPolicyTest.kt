package dev.hansel.storage

import dev.hansel.model.Fix
import dev.hansel.model.GeoMath
import dev.hansel.model.LatLng
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SamplingPolicyTest {
    private val policy = SamplingPolicy(minIntervalMillis = 3_000, minDistanceMeters = 15.0, minBearingDeltaDegrees = 20.0)
    private val origin = LatLng(30.3322, -81.6557)

    private fun fix(t: Long, p: LatLng = origin, bearing: Float? = null, speed: Float? = null) =
        Fix(point = p, timeMillis = t, bearing = bearing, speedMps = speed)

    @Test
    fun `first fix is always persisted`() {
        assertTrue(policy.shouldPersist(null, fix(0)))
    }

    @Test
    fun `nothing changed within thresholds is skipped`() {
        val prev = fix(0, bearing = 90f)
        val next = fix(1_000, GeoMath.destination(origin, 90.0, 5.0), bearing = 95f)
        assertFalse(policy.shouldPersist(prev, next))
    }

    @Test
    fun `time threshold alone triggers`() {
        val prev = fix(0)
        assertFalse(policy.shouldPersist(prev, fix(2_999)))
        assertTrue(policy.shouldPersist(prev, fix(3_000)))
    }

    @Test
    fun `distance threshold alone triggers`() {
        val prev = fix(0)
        assertFalse(policy.shouldPersist(prev, fix(1_000, GeoMath.destination(origin, 0.0, 14.0))))
        assertTrue(policy.shouldPersist(prev, fix(1_000, GeoMath.destination(origin, 0.0, 16.0))))
    }

    @Test
    fun `bearing change alone triggers and handles wraparound`() {
        val prev = fix(0, bearing = 10f)
        assertFalse(policy.shouldPersist(prev, fix(500, bearing = 25f)))
        assertTrue(policy.shouldPersist(prev, fix(500, bearing = 31f)))
        // 350 -> 10 is a 20 degree change across north.
        assertTrue(policy.shouldPersist(fix(0, bearing = 350f), fix(500, bearing = 10f)))
    }

    @Test
    fun `missing bearing never counts as a bearing change`() {
        assertFalse(policy.shouldPersist(fix(0, bearing = null), fix(500, bearing = 170f)))
        assertFalse(policy.shouldPersist(fix(0, bearing = 170f), fix(500, bearing = null)))
    }

    @Test
    fun `speed is irrelevant to the decision and is not touched`() {
        val prev = fix(0, speed = 0f)
        val absurd = fix(500, speed = 400f) // raw; policy must neither clamp nor react
        assertFalse(policy.shouldPersist(prev, absurd))
        assertTrue(absurd.speedMps == 400f)
    }
}
