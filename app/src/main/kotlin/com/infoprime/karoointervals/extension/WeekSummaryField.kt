package com.infoprime.karoointervals.extension

import android.content.Context
import android.graphics.Color
import android.widget.RemoteViews
import com.infoprime.karoointervals.R
import com.infoprime.karoointervals.core.FitnessPoint
import com.infoprime.karoointervals.core.LoadSource
import com.infoprime.karoointervals.core.Projection
import com.infoprime.karoointervals.data.Engine
import com.infoprime.karoointervals.ui.Format
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Graphical "Week" field modelled on the Intervals week card: fitness, fatigue, form and CIL
 * (now and end of week) plus time and load progress against the week's plan.
 * Small slots get a compact version with just the two progress bars.
 */
class WeekSummaryField(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val compact = config.gridSize.second < 30 || config.gridSize.first < 30
        val job = Engine.scope.launch {
            Engine.projection.collect { p ->
                emitter.updateView(build(context, p, compact))
                // Karoo accepts about one view update per second; StateFlow hands over the latest value afterwards.
                delay(1_000)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun build(context: Context, p: Projection?, compact: Boolean): RemoteViews {
        val layout = if (compact) R.layout.field_week_compact else R.layout.field_week_full
        val views = RemoteViews(context.packageName, layout)

        val timeText: (Projection) -> String = if (compact) {
            { Format.timeVsTargetShort(it.weekTimeSecs, it.weekTimeTargetSecs) }
        } else {
            { Format.timeVsTarget(it.weekTimeSecs, it.weekTimeTargetSecs) }
        }
        val loadText: (Projection) -> String = if (compact) {
            { Format.loadVsTargetShort(it.weekLoad, it.weekLoadTarget) }
        } else {
            { Format.loadVsTarget(it.weekLoad, it.weekLoadTarget) }
        }
        views.setTextViewText(R.id.time_text, p?.let(timeText) ?: DASH)
        views.setTextViewText(R.id.time_pct, Format.pctDown(p?.weekTimePct))
        setBar(views, R.id.time_bar, p?.weekTimePct, p?.weekTimeProjectedPct)
        views.setTextViewText(R.id.load_text, p?.let(loadText) ?: DASH)
        views.setTextViewText(R.id.load_pct, Format.pctDown(p?.weekLoadPct))
        setBar(views, R.id.load_bar, p?.weekLoadPct, p?.weekLoadProjectedPct)

        if (!compact) {
            fillRow(views, p?.now, R.id.now_ctl, R.id.now_atl, R.id.now_form, R.id.now_cil)
            fillRow(views, p?.weekEnd, R.id.week_ctl, R.id.week_atl, R.id.week_form, R.id.week_cil)
            views.setTextViewText(R.id.status_text, statusText(p))
        }
        return views
    }

    /** Dark fill: done so far. Light fill behind it: where the week ends if the rest of the plan is done. */
    private fun setBar(views: RemoteViews, id: Int, donePct: Double?, projectedPct: Double?) {
        views.setProgressBar(id, 100, donePct?.roundToInt()?.coerceIn(0, 100) ?: 0, false)
        views.setInt(id, "setSecondaryProgress", projectedPct?.roundToInt()?.coerceIn(0, 100) ?: 0)
    }

    private fun fillRow(views: RemoteViews, f: FitnessPoint?, ctlId: Int, atlId: Int, formId: Int, cilId: Int) {
        views.setTextViewText(ctlId, f?.ctl?.roundToInt()?.toString() ?: DASH)
        views.setTextViewText(atlId, f?.atl?.roundToInt()?.toString() ?: DASH)
        views.setTextViewText(cilId, f?.cil?.roundToInt()?.toString() ?: DASH)
        val form = f?.formPct
        views.setTextViewText(formId, Format.pct(form))
        views.setTextColor(formId, formColour(form))
    }

    private fun statusText(p: Projection?): String {
        val b = Engine.baseline.value
        return when {
            b == null -> "Open the app to connect Intervals"
            b.today != LocalDate.now().toString() -> "Intervals data from ${b.today}"
            p?.liveLoadSource == LoadSource.PLAN_RATE -> "No power: load at planned rate"
            p?.liveLoadSource == LoadSource.NONE -> "No power: time only"
            else -> ""
        }
    }

    /** Intervals form zones: transition, fresh, grey zone, optimal, high risk. */
    private fun formColour(pct: Double?): Int = when {
        pct == null -> Color.GRAY
        pct > 20 -> Color.rgb(0xE0, 0xB0, 0x30)
        pct > 5 -> Color.rgb(0x3E, 0x9B, 0xE9)
        pct > -10 -> Color.rgb(0x9E, 0x9E, 0x9E)
        pct > -30 -> Color.rgb(0x4C, 0xAF, 0x50)
        else -> Color.rgb(0xE5, 0x48, 0x48)
    }

    companion object {
        const val TYPE_ID = "week-summary"
        private const val DASH = "–"
    }
}
