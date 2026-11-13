package com.infoprime.karoointervals.core

import java.time.LocalDate

object Projector {

    /**
     * Combines the Intervals baseline with the live ride.
     *
     * Rules (checked against the Intervals week card and fitness chart):
     * - Week time and load = completed activities + live ride. The projected versions add the
     *   planned workouts not yet done (that total is what the Intervals week card shows).
     *   A planned workout paired with an activity counts through the activity, never twice.
     * - Week targets = every planned workout in the week, paired or not.
     * - Week distance = completed + live only (planned workouts carry no distance).
     * - Planned workouts on days before today that were never paired are not counted.
     * - While a ride is live, today's open plan is replaced by it (unless disabled in settings).
     */
    fun project(baseline: Baseline, live: LiveRide?, settings: ProjectionSettings = ProjectionSettings()): Projection {
        val today = LocalDate.parse(baseline.today)
        val weekEnd = LocalDate.parse(baseline.weekStart).plusDays(6)

        val pairedIds = baseline.weekActivities.mapNotNull { it.pairedEventId }.toSet()
        val openPlan = baseline.weekPlanned.filter { it.id !in pairedIds && !LocalDate.parse(it.date).isBefore(today) }
        val todaysOpenPlan = openPlan.filter { it.date == baseline.today }
        val todaysActivities = baseline.weekActivities.filter { it.date == baseline.today }

        val liveToday = live?.takeIf { it.date == baseline.today }
        val estimate = liveToday?.let { LiveLoad.estimate(it, baseline.ftpWatts, todaysOpenPlan) }
        val planReplaced = liveToday != null && settings.liveReplacesTodaysPlan

        // Today, actual only: completed activities plus the live ride.
        val actualSessions = todaysActivities.map { it.movingSecs.toDouble() to it.intensityPct } +
            listOfNotNull(liveToday?.let { it.movingSecs to estimate?.intensityPct })
        val actualLoad = todaysActivities.sumOf { it.load } + (estimate?.load ?: 0.0)
        val actualIntensity = Intensity.dailyCombined(actualSessions)

        val now = FitnessPoint(
            ctl = Ewma.step(baseline.ctlYesterday, actualLoad, Ewma.FITNESS_DAYS),
            atl = Ewma.step(baseline.atlYesterday, actualLoad, Ewma.FATIGUE_DAYS),
            cil = Ewma.step(baseline.cilYesterday, actualIntensity ?: 0.0, Ewma.CIL_DAYS),
        )

        // Today as the week projection sees it: actual, plus the open plan unless the live ride replaces it.
        val todaysPlanCounted = if (planReplaced) emptyList() else todaysOpenPlan
        val projectedTodayLoad = actualLoad + todaysPlanCounted.sumOf { it.load }
        val projectedTodayIntensity = Intensity.dailyCombined(
            actualSessions + todaysPlanCounted.map { it.movingSecs.toDouble() to it.intensityPct },
        )

        var ctl = Ewma.step(baseline.ctlYesterday, projectedTodayLoad, Ewma.FITNESS_DAYS)
        var atl = Ewma.step(baseline.atlYesterday, projectedTodayLoad, Ewma.FATIGUE_DAYS)
        var cil = Ewma.step(baseline.cilYesterday, projectedTodayIntensity ?: 0.0, Ewma.CIL_DAYS)
        var day = today.plusDays(1)
        while (!day.isAfter(weekEnd)) {
            val plan = openPlan.filter { it.date == day.toString() }
            val load = plan.sumOf { it.load }
            val intensity = Intensity.dailyCombined(plan.map { it.movingSecs.toDouble() to it.intensityPct })
            ctl = Ewma.step(ctl, load, Ewma.FITNESS_DAYS)
            atl = Ewma.step(atl, load, Ewma.FATIGUE_DAYS)
            cil = Ewma.step(cil, intensity ?: 0.0, Ewma.CIL_DAYS)
            day = day.plusDays(1)
        }

        val plannedStillToCome = openPlan.filter { it.date != baseline.today } + todaysPlanCounted
        val weekTime = baseline.weekActivities.sumOf { it.movingSecs.toDouble() } + (liveToday?.movingSecs ?: 0.0)
        val weekLoad = baseline.weekActivities.sumOf { it.load } + (estimate?.load ?: 0.0)
        val weekDistance = baseline.weekActivities.sumOf { it.distanceM } + (liveToday?.distanceM ?: 0.0)

        val timeTarget = baseline.weekPlanned.sumOf { it.movingSecs.toDouble() }.takeIf { it > 0 }
        val loadTarget = baseline.weekPlanned.sumOf { it.load }.takeIf { it > 0 }

        return Projection(
            now = now,
            weekEnd = FitnessPoint(ctl, atl, cil),
            weekTimeSecs = weekTime,
            weekTimeProjectedSecs = weekTime + plannedStillToCome.sumOf { it.movingSecs.toDouble() },
            weekTimeTargetSecs = timeTarget,
            weekLoad = weekLoad,
            weekLoadProjected = weekLoad + plannedStillToCome.sumOf { it.load },
            weekLoadTarget = loadTarget,
            weekDistanceM = weekDistance,
            liveLoad = estimate?.load ?: 0.0,
            liveIntensityPct = estimate?.intensityPct,
            liveLoadSource = estimate?.source,
        )
    }

    /**
     * Rolls fitness and fatigue forward from the last day Intervals reported to the end of [toDate],
     * using completed activity loads. Only needed if Intervals has no row for yesterday.
     */
    fun rollForward(
        fromDate: LocalDate,
        ctl: Double,
        atl: Double,
        toDate: LocalDate,
        activities: List<CompletedActivity>,
    ): Pair<Double, Double> {
        var c = ctl
        var a = atl
        var day = fromDate.plusDays(1)
        while (!day.isAfter(toDate)) {
            val load = activities.filter { it.date == day.toString() }.sumOf { it.load }
            c = Ewma.step(c, load, Ewma.FITNESS_DAYS)
            a = Ewma.step(a, load, Ewma.FATIGUE_DAYS)
            day = day.plusDays(1)
        }
        return c to a
    }
}
