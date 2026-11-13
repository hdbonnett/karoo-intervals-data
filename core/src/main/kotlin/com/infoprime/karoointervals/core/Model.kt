package com.infoprime.karoointervals.core

import kotlinx.serialization.Serializable

/**
 * A completed activity as recorded in Intervals.icu.
 *
 * @property date local date (yyyy-MM-dd) the activity started on, in the athlete's time zone
 * @property startEpochMs UTC start time, used to recognise the live ride once it has been uploaded
 * @property intensityPct Intervals intensity in percent (an IF of 0.65 is 65.0), null if unknown
 * @property pairedEventId the planned workout this activity was matched to, if any
 */
@Serializable
data class CompletedActivity(
    val id: String,
    val date: String,
    val startEpochMs: Long? = null,
    val type: String? = null,
    val movingSecs: Int = 0,
    val distanceM: Double = 0.0,
    val load: Double = 0.0,
    val intensityPct: Double? = null,
    val pairedEventId: Long? = null,
)

/** A planned workout from the Intervals.icu calendar. */
@Serializable
data class PlannedWorkout(
    val id: Long,
    val date: String,
    val name: String? = null,
    val movingSecs: Int = 0,
    val load: Double = 0.0,
    val intensityPct: Double? = null,
)

/**
 * Everything fetched from Intervals.icu before the ride. The live ride is projected on top of it.
 *
 * @property today the date the baseline was built for (yyyy-MM-dd)
 * @property ctlYesterday unrounded fitness at the end of yesterday (actual activities only)
 * @property weekActivities completed activities from [weekStart] up to and including [today]
 * @property weekPlanned all planned workouts in the week, including ones already paired with an activity
 * @property ftpWatts FTP Intervals uses for cycling load, used to turn live normalised power into load
 */
@Serializable
data class Baseline(
    val fetchedAtEpochMs: Long,
    val today: String,
    val weekStart: String,
    val ctlYesterday: Double,
    val atlYesterday: Double,
    val cilYesterday: Double,
    val weekActivities: List<CompletedActivity>,
    val weekPlanned: List<PlannedWorkout>,
    val ftpWatts: Int? = null,
)

/**
 * The ride being recorded (or just finished but not yet uploaded) on the Karoo.
 *
 * @property movingSecs recording time excluding pauses
 * @property normalizedPowerW Karoo's normalised power for the ride so far, null without a power meter
 * @property recording false once the ride has ended but has not yet appeared in Intervals
 */
data class LiveRide(
    val startEpochMs: Long,
    val date: String,
    val movingSecs: Double,
    val distanceM: Double,
    val normalizedPowerW: Double?,
    val recording: Boolean,
)

data class ProjectionSettings(
    /** Treat the live ride as today's planned session(s), so they are not counted twice. */
    val liveReplacesTodaysPlan: Boolean = true,
)

enum class LoadSource {
    /** Load from live normalised power and FTP. */
    POWER,
    /** No power: load assumed to accrue at the rate of today's planned workout. */
    PLAN_RATE,
    /** No power and no plan: the live ride adds time and distance but no load. */
    NONE,
}

data class FitnessPoint(val ctl: Double, val atl: Double, val cil: Double) {
    /**
     * Form as Intervals shows it: (fitness - fatigue) / fitness, using whole-number fitness and fatigue.
     */
    val formPct: Double?
        get() {
            val c = Math.round(ctl).toDouble()
            val a = Math.round(atl).toDouble()
            return if (c <= 0.0) null else (c - a) / c * 100.0
        }
}

data class Projection(
    /** Fitness, fatigue and CIL right now, counting today's completed activities and the live ride. */
    val now: FitnessPoint,
    /** End-of-week values, with today's plan replaced by the live ride and the rest of the plan included. */
    val weekEnd: FitnessPoint,
    /** Done so far this week: completed activities plus the live ride. */
    val weekTimeSecs: Double,
    /** Done so far plus planned workouts still to come (the Intervals week card total). */
    val weekTimeProjectedSecs: Double,
    /** Every planned workout in the week, paired or not. */
    val weekTimeTargetSecs: Double?,
    val weekLoad: Double,
    val weekLoadProjected: Double,
    val weekLoadTarget: Double?,
    val weekDistanceM: Double,
    val liveLoad: Double,
    val liveIntensityPct: Double?,
    val liveLoadSource: LoadSource?,
) {
    /** Done so far as a percentage of the week's plan. Can pass 100. */
    val weekTimePct: Double?
        get() = weekTimeTargetSecs?.takeIf { it > 0 }?.let { weekTimeSecs / it * 100.0 }

    val weekLoadPct: Double?
        get() = weekLoadTarget?.takeIf { it > 0 }?.let { weekLoad / it * 100.0 }

    /** Where the week ends up if the rest of the plan is done, as a percentage of the plan. */
    val weekTimeProjectedPct: Double?
        get() = weekTimeTargetSecs?.takeIf { it > 0 }?.let { weekTimeProjectedSecs / it * 100.0 }

    val weekLoadProjectedPct: Double?
        get() = weekLoadTarget?.takeIf { it > 0 }?.let { weekLoadProjected / it * 100.0 }
}
