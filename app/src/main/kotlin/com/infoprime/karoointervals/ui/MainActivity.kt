package com.infoprime.karoointervals.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.infoprime.karoointervals.R
import com.infoprime.karoointervals.core.Baseline
import com.infoprime.karoointervals.core.LiveRide
import com.infoprime.karoointervals.core.Projection
import com.infoprime.karoointervals.core.ProjectionSettings
import com.infoprime.karoointervals.core.Projector
import com.infoprime.karoointervals.data.Engine
import com.infoprime.karoointervals.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.DayOfWeek
import java.util.Date
import kotlin.math.roundToInt

/**
 * Settings and test screen. Also accepts credentials from adb, which saves typing an API key on the Karoo:
 *
 *   adb shell am start -n com.infoprime.karoointervals/.ui.MainActivity --es athlete_id i12345 --es api_key YOURKEY
 */
class MainActivity : Activity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val weekStarts = listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)

    private lateinit var athleteId: EditText
    private lateinit var apiKey: EditText
    private lateinit var weekStart: Spinner
    private lateinit var ftp: EditText
    private lateinit var replacePlan: CheckBox
    private lateinit var status: TextView
    private lateinit var simResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        setContentView(R.layout.activity_main)

        athleteId = findViewById(R.id.athlete_id)
        apiKey = findViewById(R.id.api_key)
        weekStart = findViewById(R.id.week_start)
        ftp = findViewById(R.id.ftp)
        replacePlan = findViewById(R.id.replace_plan)
        status = findViewById(R.id.status)
        simResult = findViewById(R.id.sim_result)

        weekStart.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            weekStarts.map { it.name.lowercase().replaceFirstChar(Char::uppercase) },
        )
        showSettings(Engine.settings.value)

        findViewById<Button>(R.id.save).setOnClickListener { saveAndRefresh(readSettings()) }
        findViewById<Button>(R.id.simulate).setOnClickListener { simulate() }

        uiScope.launch {
            combine(Engine.status, Engine.baseline, Engine.projection, Engine.live) { s, b, p, l ->
                statusText(s, b, p, l)
            }.collect { status.text = it }
        }
        handleCredentialIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCredentialIntent(intent)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun handleCredentialIntent(intent: Intent?) {
        val id = intent?.getStringExtra("athlete_id")
        val key = intent?.getStringExtra("api_key")
        if (id.isNullOrBlank() && key.isNullOrBlank()) return
        val current = Engine.settings.value
        val updated = current.copy(
            athleteId = id?.takeIf { it.isNotBlank() } ?: current.athleteId,
            apiKey = key?.takeIf { it.isNotBlank() } ?: current.apiKey,
        )
        showSettings(updated)
        saveAndRefresh(updated)
    }

    private fun showSettings(s: Settings) {
        athleteId.setText(s.athleteId)
        apiKey.setText(s.apiKey)
        weekStart.setSelection(weekStarts.indexOf(s.weekStart).coerceAtLeast(0))
        ftp.setText(s.ftpOverride?.toString().orEmpty())
        replacePlan.isChecked = s.liveReplacesTodaysPlan
    }

    private fun readSettings() = Settings(
        athleteId = athleteId.text.toString().trim(),
        apiKey = apiKey.text.toString().trim(),
        weekStart = weekStarts[weekStart.selectedItemPosition.coerceAtLeast(0)],
        liveReplacesTodaysPlan = replacePlan.isChecked,
        ftpOverride = ftp.text.toString().toIntOrNull()?.takeIf { it > 0 },
    )

    private fun saveAndRefresh(settings: Settings) {
        Engine.saveSettings(settings)
        uiScope.launch {
            val result = Engine.refresh()
            val msg = result.fold({ "Updated from Intervals" }, { "Refresh failed: ${it.message}" })
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun simulate() {
        val b = Engine.baseline.value
        if (b == null) {
            simResult.text = "Refresh from Intervals first."
            return
        }
        val minutes = findViewById<EditText>(R.id.sim_minutes).text.toString().toDoubleOrNull() ?: 60.0
        val np = findViewById<EditText>(R.id.sim_np).text.toString().toDoubleOrNull()
        val km = findViewById<EditText>(R.id.sim_km).text.toString().toDoubleOrNull() ?: 0.0
        val ride = LiveRide(System.currentTimeMillis(), b.today, minutes * 60, km * 1000, np, recording = true)
        val p = Projector.project(b, ride, ProjectionSettings(Engine.settings.value.liveReplacesTodaysPlan))
        simResult.text = buildString {
            appendLine("Ride: ${minutes.roundToInt()} min, NP ${np?.roundToInt() ?: "none"}, $km km")
            appendLine("Ride load ${p.liveLoad.roundToInt()} (${p.liveLoadSource}), intensity ${Format.pct(p.liveIntensityPct)}")
            append(projectionText(p))
        }
    }

    private fun statusText(s: Engine.Status, b: Baseline?, p: Projection?, live: LiveRide?): String = buildString {
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        appendLine("Last update: " + (s.lastSuccessEpochMs?.let { df.format(Date(it)) } ?: "never") +
            if (s.refreshing) " (refreshing…)" else "")
        s.lastError?.let { appendLine("Error: $it") }
        if (b == null) return@buildString
        appendLine("Data for ${b.today}, week from ${b.weekStart}")
        appendLine(
            "End of yesterday: fitness %.1f  fatigue %.1f  CIL %.1f".format(b.ctlYesterday, b.atlYesterday, b.cilYesterday),
        )
        appendLine("FTP for live load: ${b.ftpWatts ?: "unknown (set an override)"}")
        val paired = b.weekActivities.mapNotNull { it.pairedEventId }.toSet()
        appendLine(
            "This week: ${b.weekActivities.size} activities, ${b.weekPlanned.size} planned " +
                "(${b.weekPlanned.count { it.id in paired }} done)",
        )
        live?.let {
            appendLine(
                "Ride ${if (it.recording) "recording" else "finished, awaiting upload"}: " +
                    "${Format.duration(it.movingSecs)}, NP ${it.normalizedPowerW?.roundToInt() ?: "none"}",
            )
        }
        appendLine(
            "Karoo workout: " + (Engine.karooWorkoutLengthSecs.value?.let { len ->
                "running" + (Engine.activeWorkout.value?.let { w -> ", matched '${w.name}' load ${w.load.roundToInt()}" }
                    ?: ", no plan today") + if (len > 0) " (${Format.duration(len)})" else ""
            } ?: "none"),
        )
        p?.let { append(projectionText(it)) }
    }

    private fun projectionText(p: Projection): String = buildString {
        appendLine("            Fitness Fatigue  Form  CIL")
        appendLine(row("Now", p.now.ctl, p.now.atl, p.now.formPct, p.now.cil))
        appendLine(row("End of week", p.weekEnd.ctl, p.weekEnd.atl, p.weekEnd.formPct, p.weekEnd.cil))
        appendLine("Week time  ${Format.timeVsTarget(p.weekTimeSecs, p.weekTimeTargetSecs)}  ${Format.pctDown(p.weekTimePct)}")
        appendLine("  projected ${Format.duration(p.weekTimeProjectedSecs)}  ${Format.pctDown(p.weekTimeProjectedPct)}")
        appendLine("Week load  ${Format.loadVsTarget(p.weekLoad, p.weekLoadTarget)}  ${Format.pctDown(p.weekLoadPct)}")
        appendLine("  projected ${p.weekLoadProjected.roundToInt()}  ${Format.pctDown(p.weekLoadProjectedPct)}")
        append("Week dist  ${Format.km(p.weekDistanceM)}")
    }

    private fun row(label: String, ctl: Double, atl: Double, form: Double?, cil: Double) =
        "%-11s %7d %7d %5s %4d".format(label, ctl.roundToInt(), atl.roundToInt(), Format.pct(form), cil.roundToInt())
}
