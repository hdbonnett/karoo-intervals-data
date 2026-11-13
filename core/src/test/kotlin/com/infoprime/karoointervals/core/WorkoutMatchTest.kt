package com.infoprime.karoointervals.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkoutMatchTest {

    private val sunday = Baseline(
        fetchedAtEpochMs = 0,
        today = "2026-11-08",
        weekStart = "2026-11-02",
        ctlYesterday = 45.0,
        atlYesterday = 67.0,
        cilYesterday = 41.0,
        weekActivities = emptyList(),
        weekPlanned = listOf(
            PlannedWorkout(1, "2026-11-07", "Z2 + 3x10min Tempo", 180 * 60, 136.0, 67.0),
            PlannedWorkout(2, "2026-11-08", "Sunday Bunch", 150 * 60, 106.0, 65.0),
        ),
    )

    @Test
    fun `single planned workout today is the target`() {
        assertEquals(106.0, WorkoutMatch.plannedFor(sunday, workoutLengthSecs = 9000.0)?.load)
        assertEquals(106.0, WorkoutMatch.plannedFor(sunday, workoutLengthSecs = null)?.load)
    }

    @Test
    fun `nothing planned today gives no target`() {
        assertNull(WorkoutMatch.plannedFor(sunday.copy(today = "2026-11-09"), 3600.0))
    }

    @Test
    fun `a workout already done today is not the target`() {
        val done = sunday.copy(
            weekActivities = listOf(CompletedActivity("i1", "2026-11-08", load = 100.0, pairedEventId = 2)),
        )
        assertNull(WorkoutMatch.plannedFor(done, 9000.0))
    }

    @Test
    fun `two planned today picks the closest duration`() {
        val twoToday = sunday.copy(
            weekPlanned = sunday.weekPlanned + PlannedWorkout(3, "2026-11-08", "Openers", 30 * 60, 25.0, 75.0),
        )
        assertEquals(25.0, WorkoutMatch.plannedFor(twoToday, 32 * 60.0)?.load)
        assertEquals(106.0, WorkoutMatch.plannedFor(twoToday, 145 * 60.0)?.load)
    }
}
