package com.infoprime.karoointervals.core

import kotlin.math.abs

object WorkoutMatch {

    /**
     * The Intervals planned workout behind the workout running on the Karoo.
     *
     * Karoo says a workout is running and how long is left, but not its name or load, so it is
     * matched to today's planned workouts that haven't been done yet:
     * - one candidate: that workout;
     * - several: the one whose planned duration is closest to the Karoo workout's length
     *   ([workoutLengthSecs], the longest "time remaining" seen, i.e. its length at the start).
     *
     * Returns null when nothing is planned today, so the field shows N/A.
     */
    fun plannedFor(baseline: Baseline, workoutLengthSecs: Double?): PlannedWorkout? {
        val paired = baseline.weekActivities.mapNotNull { it.pairedEventId }.toSet()
        val candidates = baseline.weekPlanned.filter { it.date == baseline.today && it.id !in paired }
        return when {
            candidates.isEmpty() -> null
            candidates.size == 1 || workoutLengthSecs == null -> candidates.first()
            else -> candidates.minBy { abs(it.movingSecs - workoutLengthSecs) }
        }
    }
}
