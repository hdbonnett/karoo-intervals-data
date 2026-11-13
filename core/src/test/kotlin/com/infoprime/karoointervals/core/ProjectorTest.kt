package com.infoprime.karoointervals.core

import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Figures in these tests come from a real Intervals.icu week (Mon 2 – Sun 8 Nov 2026),
 * so the maths is checked against what the Intervals week card and fitness chart showed.
 */
class ProjectorTest {

    @Test
    fun `fatigue and fitness follow the Intervals daily values`() {
        // Loads 2–8 Nov (5–8 Nov were planned at the time). Intervals showed:
        // fatigue 57, 53, 46, 44, 56, 67, 72 and fitness 43, 42, 41, 41, 43, 45, 47.
        val loads = listOf(0.0, 29.0, 0.0, 32.0, 132.0, 136.0, 106.0)
        var atl = 66.0 // 1 Nov, as shown (whole number)
        var ctl = 43.6 // 1 Nov shown as 44; the unrounded value is not exposed
        val atlSeen = mutableListOf<Int>()
        val ctlSeen = mutableListOf<Int>()
        for (load in loads) {
            atl = Ewma.step(atl, load, Ewma.FATIGUE_DAYS)
            ctl = Ewma.step(ctl, load, Ewma.FITNESS_DAYS)
            atlSeen += atl.roundToInt()
            ctlSeen += ctl.roundToInt()
        }
        assertEquals(listOf(57, 53, 46, 44, 56, 67, 72), atlSeen)
        assertEquals(listOf(43, 42, 41, 41, 43, 45, 47), ctlSeen)
    }

    @Test
    fun `week totals are done so far, projected totals match the Intervals week card`() {
        val p = Projector.project(week45(), live = null)

        // Done so far: Tuesday only.
        assertEquals(85 * 60.0, p.weekTimeSecs)
        assertEquals(29.0, p.weekLoad)
        // Projected = the Intervals week card totals.
        assertEquals(523 * 60.0, p.weekTimeProjectedSecs) // 8h43m
        assertEquals(435.0, p.weekLoadProjected)
        assertEquals(528 * 60.0, p.weekTimeTargetSecs) // 8h48m
        assertEquals(473.0, p.weekLoadTarget)
        assertEquals(25_000.0, p.weekDistanceM)
        assertEquals(16, p.weekTimePct!!.toInt()) // 85 of 528 min
        assertEquals(6, p.weekLoadPct!!.toInt()) // 29 of 473
        assertEquals(99, p.weekTimeProjectedPct!!.toInt())
        assertEquals(91, p.weekLoadProjectedPct!!.toInt())
    }

    @Test
    fun `end of week fitness, fatigue and form match the card`() {
        val p = Projector.project(week45(), live = null)
        assertEquals(47, p.weekEnd.ctl.roundToInt())
        assertEquals(72, p.weekEnd.atl.roundToInt())
        assertEquals(-53, p.weekEnd.formPct!!.roundToInt())
    }

    @Test
    fun `fitness now counts only what has actually been done today`() {
        val p = Projector.project(week45(), live = null)
        // Nothing ridden yet today: one rest-day step from yesterday's values.
        assertEquals(40, p.now.ctl.roundToInt())
        assertEquals(40, p.now.atl.roundToInt())
    }

    @Test
    fun `live ride with power replaces today's plan`() {
        val live = LiveRide(
            startEpochMs = 0, date = "2026-11-05", movingSecs = 45 * 60.0,
            distanceM = 18_000.0, normalizedPowerW = 120.0, recording = true,
        )
        val p = Projector.project(week45(), live)

        // IF 120/175 = 0.686, 45 min -> load 35.3, intensity 68.6%
        assertEquals(LoadSource.POWER, p.liveLoadSource)
        assertEquals(35.3, p.liveLoad, 0.1)
        assertEquals(68.6, p.liveIntensityPct!!, 0.1)
        // Planned Thursday (45 min, load 32) is replaced, not added.
        assertEquals((85 + 45) * 60.0, p.weekTimeSecs)
        assertEquals(29 + 35.3, p.weekLoad, 0.1)
        assertEquals((85 + 45 + 63 + 180 + 150) * 60.0, p.weekTimeProjectedSecs)
        assertEquals(29 + 35.3 + 132 + 136 + 106, p.weekLoadProjected, 0.1)
        assertEquals(43_000.0, p.weekDistanceM)
        // Fitness now includes the ride.
        assertEquals(Ewma.step(41.26, 35.3, Ewma.FITNESS_DAYS), p.now.ctl, 0.01)
    }

    @Test
    fun `live ride without power accrues at the planned rate`() {
        val live = LiveRide(0, "2026-11-05", 22.5 * 60, 9_000.0, normalizedPowerW = null, recording = true)
        val p = Projector.project(week45(), live)
        assertEquals(LoadSource.PLAN_RATE, p.liveLoadSource)
        assertEquals(16.0, p.liveLoad, 0.01) // half of the planned 32
    }

    @Test
    fun `plan can be kept alongside the live ride`() {
        val live = LiveRide(0, "2026-11-05", 20 * 60.0, 8_000.0, 150.0, recording = true)
        val p = Projector.project(week45(), live, ProjectionSettings(liveReplacesTodaysPlan = false))
        assertEquals((85 + 20) * 60.0, p.weekTimeSecs)
        assertEquals((85 + 20 + 45 + 63 + 180 + 150) * 60.0, p.weekTimeProjectedSecs)
    }

    @Test
    fun `a ride from another day is ignored`() {
        val live = LiveRide(0, "2026-11-04", 3600.0, 30_000.0, 150.0, recording = false)
        val p = Projector.project(week45(), live)
        assertNull(p.liveLoadSource)
        assertEquals(29.0, p.weekLoad)
        assertEquals(435.0, p.weekLoadProjected)
    }

    @Test
    fun `skipping a planned session never raises the percentage`() {
        // Friday's session missed: on Saturday it is in the past and unpaired.
        val saturday = week45().copy(today = "2026-11-07")
        val p = Projector.project(saturday, live = null)
        assertEquals(85 * 60.0, p.weekTimeSecs)
        assertEquals(528 * 60.0, p.weekTimeTargetSecs) // target unchanged
        assertEquals(16, p.weekTimePct!!.toInt())
        assertEquals((85 + 180 + 150) * 60.0, p.weekTimeProjectedSecs)
    }

    @Test
    fun `no plan in the week gives no targets`() {
        val p = Projector.project(week45().copy(weekPlanned = emptyList()), live = null)
        assertNull(p.weekTimeTargetSecs)
        assertNull(p.weekLoadPct)
        assertEquals(85 * 60.0, p.weekTimeSecs)
    }

    @Test
    fun `CIL is a 42 day average of daily intensity with rest days as zero`() {
        val start = LocalDate.parse("2026-01-01")
        val end = start.plusDays(399)
        val everyDay = (0 until 400).map { act(start.plusDays(it.toLong()).toString(), 3600, 65.0) }
        assertEquals(65.0, CilHistory.cilAtEndOf(start, end, everyDay), 0.1)

        val everyOtherDay = everyDay.filterIndexed { i, _ -> i % 2 == 0 }
        assertEquals(32.5, CilHistory.cilAtEndOf(start, end, everyOtherDay), 1.0)
    }

    @Test
    fun `intensity accepts percent or fraction and weights by duration`() {
        assertEquals(65.0, Intensity.normalisePct(0.65)!!, 1e-9)
        assertEquals(65.0, Intensity.normalisePct(65.0)!!, 1e-9)
        assertNull(Intensity.normalisePct(0.0))
        val combined = Intensity.dailyCombined(listOf(3600.0 to 60.0, 1800.0 to 90.0, 600.0 to null))
        assertNotNull(combined)
        assertEquals(70.0, combined, 1e-9)
    }

    @Test
    fun `form is shown from whole-number fitness and fatigue`() {
        assertEquals(-7, FitnessPoint(41.0, 44.0, 40.0).formPct!!.roundToInt())
        assertNull(FitnessPoint(0.2, 5.0, 0.0).formPct)
    }

    @Test
    fun `roll forward fills a missing day`() {
        val (ctl, atl) = Projector.rollForward(
            LocalDate.parse("2026-11-02"), 43.0, 57.0, LocalDate.parse("2026-11-03"),
            listOf(act("2026-11-03", 85 * 60, 50.0, load = 29.0)),
        )
        assertEquals(Ewma.step(43.0, 29.0, Ewma.FITNESS_DAYS), ctl, 1e-9)
        assertEquals(Ewma.step(57.0, 29.0, Ewma.FATIGUE_DAYS), atl, 1e-9)
    }

    private fun act(date: String, secs: Int, intensity: Double?, load: Double = 0.0) =
        CompletedActivity(id = "a$date", date = date, movingSecs = secs, intensityPct = intensity, load = load)

    /** Week 45 as it stood on Thursday morning, before the planned fatigue test. */
    private fun week45() = Baseline(
        fetchedAtEpochMs = 0,
        today = "2026-11-05",
        weekStart = "2026-11-02",
        ctlYesterday = 41.26,
        atlYesterday = 46.34,
        cilYesterday = 40.0,
        weekActivities = listOf(
            CompletedActivity(
                id = "i1", date = "2026-11-03", movingSecs = 85 * 60, distanceM = 25_000.0,
                load = 29.0, intensityPct = 47.0, pairedEventId = 150487425,
            ),
        ),
        weekPlanned = listOf(
            PlannedWorkout(150487425, "2026-11-03", "Z2 Endurance 1:30", 90 * 60, 67.0, 70.0),
            PlannedWorkout(150487431, "2026-11-05", "Fatigue Test (short)", 45 * 60, 32.0, 65.0),
            PlannedWorkout(150487437, "2026-11-06", "Ramp Test + Z2", 63 * 60, 132.0, 110.0),
            PlannedWorkout(150487442, "2026-11-07", "Z2 + 3x10min Tempo", 180 * 60, 136.0, 67.0),
            PlannedWorkout(150487447, "2026-11-08", "Sunday Bunch", 150 * 60, 106.0, 65.0),
        ),
        ftpWatts = 175,
    )
}
