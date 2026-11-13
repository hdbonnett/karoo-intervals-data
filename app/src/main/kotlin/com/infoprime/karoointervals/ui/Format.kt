package com.infoprime.karoointervals.ui

import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object Format {
    /** 31380 s -> "8h43m" */
    fun duration(secs: Double): String {
        val totalMin = (secs / 60.0).roundToLong()
        return "${totalMin / 60}h${(totalMin % 60).toString().padStart(2, '0')}m"
    }

    fun pct(value: Double?): String = value?.let { "${it.roundToInt()}%" } ?: "–"

    /** Week percentages: Intervals rounds these down (435 of 473 is 91%). */
    fun pctDown(value: Double?): String = value?.let { "${floor(it).toInt()}%" } ?: "–"

    /** Short forms for narrow slots: "8h43/8h48", "435/473". */
    fun durationShort(secs: Double): String = duration(secs).removeSuffix("m")

    fun timeVsTargetShort(secs: Double, targetSecs: Double?): String =
        durationShort(secs) + (targetSecs?.let { "/${durationShort(it)}" } ?: "")

    fun loadVsTargetShort(load: Double, target: Double?): String =
        load.roundToInt().toString() + (target?.let { "/${it.roundToInt()}" } ?: "")

    fun distance(metres: Double, imperial: Boolean): String =
        if (imperial) String.format(Locale.ROOT, "%.1f mi", metres / 1609.344) else km(metres)

    fun timeVsTarget(secs: Double, targetSecs: Double?): String =
        duration(secs) + (targetSecs?.let { " / ${duration(it)}" } ?: "")

    fun loadVsTarget(load: Double, target: Double?): String =
        load.roundToInt().toString() + (target?.let { " / ${it.roundToInt()}" } ?: "")

    fun km(metres: Double): String = String.format(Locale.ROOT, "%.1f km", metres / 1000.0)
}
