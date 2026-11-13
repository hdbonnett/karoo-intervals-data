package com.infoprime.karoointervals.core

import java.time.LocalDate
import kotlin.math.exp

/**
 * Exponentially weighted moving averages as Intervals.icu uses them.
 *
 * Checked against real Intervals values: fatigue over a week of mixed rest and ride days only
 * matches with decay e^(-1/days), not the simpler 1/days form.
 */
object Ewma {
    const val FITNESS_DAYS = 42.0
    const val FATIGUE_DAYS = 7.0
    const val CIL_DAYS = 42.0

    fun step(previous: Double, todaysValue: Double, days: Double): Double {
        val decay = exp(-1.0 / days)
        return previous * decay + todaysValue * (1.0 - decay)
    }
}

object Intensity {
    /** Intervals reports intensity in percent; accept a fraction too, in case an endpoint differs. */
    fun normalisePct(value: Double?): Double? = when {
        value == null || value.isNaN() || value <= 0.0 -> null
        value <= 3.0 -> value * 100.0
        else -> value
    }

    /**
     * One intensity value for a day with several sessions: duration-weighted average.
     *
     * Intervals' exact rule for multi-session days is not documented; this is the assumption
     * to check first if CIL drifts from the Intervals chart on such days.
     */
    fun dailyCombined(sessions: List<Pair<Double, Double?>>): Double? {
        val withIntensity = sessions.filter { it.second != null && it.first > 0 }
        val totalSecs = withIntensity.sumOf { it.first }
        if (totalSecs <= 0) return null
        return withIntensity.sumOf { it.first * it.second!! } / totalSecs
    }
}

object CilHistory {
    /**
     * Chronic intensity load at the end of [endDate]: a 42-day EWMA of daily intensity in percent,
     * with days without training counting as zero. Starts from zero at [startDate], so give it
     * several months of history (180 days leaves about 1% of the starting error).
     */
    fun cilAtEndOf(startDate: LocalDate, endDate: LocalDate, activities: List<CompletedActivity>): Double {
        val byDate = activities.groupBy { it.date }
        var cil = 0.0
        var day = startDate
        while (!day.isAfter(endDate)) {
            val sessions = byDate[day.toString()].orEmpty().map { it.movingSecs.toDouble() to it.intensityPct }
            cil = Ewma.step(cil, Intensity.dailyCombined(sessions) ?: 0.0, Ewma.CIL_DAYS)
            day = day.plusDays(1)
        }
        return cil
    }
}

/** Load and intensity for the ride in progress. */
data class LiveEstimate(val load: Double, val intensityPct: Double?, val source: LoadSource)

object LiveLoad {
    private const val MIN_SECS_FOR_POWER = 60.0

    fun estimate(live: LiveRide, ftpWatts: Int?, todaysOpenPlan: List<PlannedWorkout>): LiveEstimate {
        val np = live.normalizedPowerW
        if (np != null && np > 0 && ftpWatts != null && ftpWatts > 0 && live.movingSecs >= MIN_SECS_FOR_POWER) {
            val intensityFactor = np / ftpWatts
            val load = intensityFactor * intensityFactor * (live.movingSecs / 3600.0) * 100.0
            return LiveEstimate(load, intensityFactor * 100.0, LoadSource.POWER)
        }
        val planSecs = todaysOpenPlan.sumOf { it.movingSecs }.toDouble()
        if (planSecs > 0) {
            val fraction = (live.movingSecs / planSecs).coerceIn(0.0, 1.0)
            val planIntensity = Intensity.dailyCombined(todaysOpenPlan.map { it.movingSecs.toDouble() to it.intensityPct })
            return LiveEstimate(todaysOpenPlan.sumOf { it.load } * fraction, planIntensity, LoadSource.PLAN_RATE)
        }
        return LiveEstimate(0.0, null, LoadSource.NONE)
    }
}
