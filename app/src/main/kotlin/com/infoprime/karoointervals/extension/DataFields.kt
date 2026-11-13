package com.infoprime.karoointervals.extension

import android.content.Context
import android.view.Gravity
import android.widget.RemoteViews
import com.infoprime.karoointervals.R
import com.infoprime.karoointervals.core.Projection
import com.infoprime.karoointervals.data.Engine
import com.infoprime.karoointervals.ui.Format
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * A standard numeric Karoo field showing a whole number taken from the current [Projection].
 * Karoo draws it; values are rounded here as Intervals rounds them.
 */
class ProjectionField(
    extension: String,
    typeId: String,
    private val value: (Projection) -> Double?,
) : DataTypeImpl(extension, typeId) {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = Engine.scope.launch {
            Engine.projection
                .map { p -> p?.let(value)?.takeUnless { it.isNaN() }?.let { Math.round(it).toDouble() } }
                .distinctUntilChanged()
                .collect { v ->
                    emitter.onNext(
                        if (v == null) {
                            StreamState.NotAvailable
                        } else {
                            StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to v)))
                        },
                    )
                }
        }
        emitter.setCancellable { job.cancel() }
    }
}

/**
 * A field whose value needs formatting Karoo's numeric fields can't do reliably (a duration,
 * or a distance in km/mi with a decimal). Drawn as text that shrinks to fit the slot, in the
 * field's configured alignment; Karoo still draws the field's name and icon above it.
 * (Setting a size from ViewConfig.textSize would switch auto-sizing off and clip "25.4 km".)
 */
open class TextValueField(
    extension: String,
    typeId: String,
    private val textFlow: () -> Flow<String>,
) : DataTypeImpl(extension, typeId) {

    /** Text from the current [Projection]. */
    constructor(extension: String, typeId: String, text: (Projection, imperial: Boolean) -> String) :
        this(extension, typeId, {
            Engine.projection.combine(Engine.imperialUnits) { p, imperial -> p?.let { text(it, imperial) } ?: "–" }
        })

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true))
        val gravity = when (config.alignment) {
            ViewConfig.Alignment.LEFT -> Gravity.START
            ViewConfig.Alignment.CENTER -> Gravity.CENTER_HORIZONTAL
            ViewConfig.Alignment.RIGHT -> Gravity.END
        } or Gravity.BOTTOM
        val job = Engine.scope.launch {
            textFlow()
                .distinctUntilChanged()
                .collect { value ->
                    val views = RemoteViews(context.packageName, R.layout.field_text_value)
                    views.setTextViewText(R.id.value, value)
                    views.setInt(R.id.value, "setGravity", gravity)
                    emitter.updateView(views)
                    delay(1_000) // Karoo accepts about one view update per second
                }
        }
        emitter.setCancellable { job.cancel() }
    }
}

object DataFields {
    /** Intervals shows week percentages rounded down (435 of 473 is 91%, not 92%). */
    private fun pctDown(value: Double?) = value?.let { floor(it) }

    fun all(extension: String): List<DataTypeImpl> = listOf(
        // Right now: yesterday's closing values plus everything done today, including the live ride.
        ProjectionField(extension, "now-fitness") { it.now.ctl },
        ProjectionField(extension, "now-fatigue") { it.now.atl },
        ProjectionField(extension, "now-form") { it.now.formPct },
        ProjectionField(extension, "now-cil") { it.now.cil },
        // End of the week: the live ride in place of today's plan, then the rest of the plan.
        ProjectionField(extension, "week-fitness") { it.weekEnd.ctl },
        ProjectionField(extension, "week-fatigue") { it.weekEnd.atl },
        ProjectionField(extension, "week-form") { it.weekEnd.formPct },
        ProjectionField(extension, "week-cil") { it.weekEnd.cil },
        // Week totals done so far (completed + this ride), and as a percentage of the week's plan.
        TextValueField(extension, "week-time") { p, _ -> Format.duration(p.weekTimeSecs) },
        ProjectionField(extension, "week-time-pct") { pctDown(it.weekTimePct) },
        ProjectionField(extension, "week-load") { it.weekLoad },
        ProjectionField(extension, "week-load-pct") { pctDown(it.weekLoadPct) },
        // Projected: done so far plus planned workouts still to come (the Intervals week card totals).
        TextValueField(extension, "week-time-projected") { p, _ -> Format.duration(p.weekTimeProjectedSecs) },
        ProjectionField(extension, "week-load-projected") { it.weekLoadProjected },
        TextValueField(extension, "week-distance") { p, imperial -> Format.distance(p.weekDistanceM, imperial) },
        // Planned load of the workout running on the Karoo, from the Intervals calendar.
        TextValueField(extension, "workout-target-load") {
            Engine.activeWorkout.map { w -> w?.load?.takeIf { it > 0 }?.let { Math.round(it).toString() } ?: "N/A" }
        },
        WeekSummaryField(extension),
    )
}
