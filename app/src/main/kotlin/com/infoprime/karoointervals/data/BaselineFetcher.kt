package com.infoprime.karoointervals.data

import com.infoprime.karoointervals.core.Baseline
import com.infoprime.karoointervals.core.CilHistory
import com.infoprime.karoointervals.core.Projector
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Builds the pre-ride [Baseline] from three Intervals calls: wellness, activities and planned workouts. */
class BaselineFetcher(private val api: IntervalsApi) {

    suspend fun fetch(settings: Settings, today: LocalDate, nowEpochMs: Long): Baseline {
        val yesterday = today.minusDays(1)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(settings.weekStart))
        val weekEnd = weekStart.plusDays(6)

        // 180 days of activities: enough for CIL to settle, and the source of this week's totals.
        val activityDtos = api.activities(today.minusDays(HISTORY_DAYS), today)
        val activities = activityDtos.mapNotNull { it.toCore() }

        // Yesterday's closing fitness and fatigue. If Intervals has no row for yesterday,
        // roll the most recent row forward with activity loads.
        val wellness = api.wellness(today.minusDays(14), yesterday)
            .filter { it.ctl != null && it.atl != null && it.id <= yesterday.toString() }
            .maxByOrNull { it.id }
            ?: throw IntervalsException("No fitness data in Intervals for the last 14 days")
        val (ctl, atl) = Projector.rollForward(
            LocalDate.parse(wellness.id), wellness.ctl!!, wellness.atl!!, yesterday, activities,
        )

        val planned = api.plannedWorkouts(weekStart, weekEnd).mapNotNull { it.toCore() }

        return Baseline(
            fetchedAtEpochMs = nowEpochMs,
            today = today.toString(),
            weekStart = weekStart.toString(),
            ctlYesterday = ctl,
            atlYesterday = atl,
            cilYesterday = CilHistory.cilAtEndOf(today.minusDays(HISTORY_DAYS), yesterday, activities),
            weekActivities = activities.filter { it.date >= weekStart.toString() && it.date <= today.toString() },
            weekPlanned = planned,
            ftpWatts = settings.ftpOverride ?: latestRideFtp(activityDtos),
        )
    }

    /** The FTP Intervals used for the most recent ride, i.e. the one it will use to score this ride. */
    private fun latestRideFtp(activities: List<ActivityDto>): Int? = activities
        .filter { it.ftp != null && it.ftp > 0 && it.type?.contains("Ride") == true }
        .maxByOrNull { it.startDateLocal.orEmpty() }
        ?.ftp

    companion object {
        const val HISTORY_DAYS = 180L
    }
}
