package com.infoprime.karoointervals.data

import android.content.Context
import android.util.Log
import com.infoprime.karoointervals.core.Baseline
import com.infoprime.karoointervals.core.LiveRide
import com.infoprime.karoointervals.core.PlannedWorkout
import com.infoprime.karoointervals.core.WorkoutMatch
import com.infoprime.karoointervals.core.Projection
import com.infoprime.karoointervals.core.ProjectionSettings
import com.infoprime.karoointervals.core.Projector
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

/**
 * Shared state for the extension service, the data fields and the settings screen
 * (all run in the app's single process).
 */
object Engine {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = MutableStateFlow(Settings())
    val baseline = MutableStateFlow<Baseline?>(null)
    val live = MutableStateFlow<LiveRide?>(null)
    val status = MutableStateFlow(Status())

    /** From the Karoo user profile: show distances in miles. */
    val imperialUnits = MutableStateFlow(false)

    /**
     * Length of the workout running on the Karoo (longest "time remaining" seen), or null when
     * no workout is running. Zero means running but its length isn't known yet.
     */
    val karooWorkoutLengthSecs = MutableStateFlow<Double?>(null)

    /** Set by the extension service while it is connected to Karoo OS. */
    @Volatile
    var karoo: KarooSystemService? = null

    val projection: StateFlow<Projection?> = combine(baseline, live, settings) { b, l, s ->
        b?.let { Projector.project(it, l, ProjectionSettings(s.liveReplacesTodaysPlan)) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The Intervals planned workout matching the workout running on the Karoo; null when none is running. */
    val activeWorkout: StateFlow<PlannedWorkout?> = combine(baseline, karooWorkoutLengthSecs) { b, length ->
        if (b == null || length == null) null else WorkoutMatch.plannedFor(b, length.takeIf { it > 0 })
    }.stateIn(scope, SharingStarted.Eagerly, null)

    data class Status(
        val lastSuccessEpochMs: Long? = null,
        val lastError: String? = null,
        val refreshing: Boolean = false,
    )

    private lateinit var appContext: Context
    private lateinit var store: SettingsStore
    private val refreshLock = Mutex()
    private val cacheFile get() = File(appContext.filesDir, "baseline.json")

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        store = SettingsStore(appContext)
        settings.value = store.load()
        baseline.value = runCatching {
            IntervalsApi.json.decodeFromString(Baseline.serializer(), cacheFile.readText())
        }.getOrNull()
        status.value = Status(lastSuccessEpochMs = baseline.value?.fetchedAtEpochMs)
    }

    fun saveSettings(newSettings: Settings) {
        store.save(newSettings)
        settings.value = newSettings
    }

    /** True if the cached baseline is from an earlier day or older than [maxAgeMs]. */
    fun baselineStale(maxAgeMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val b = baseline.value ?: return true
        return b.today != LocalDate.now().toString() || nowEpochMs - b.fetchedAtEpochMs > maxAgeMs
    }

    /** Fetches a fresh baseline from Intervals. Safe to call from anywhere; concurrent calls are merged. */
    suspend fun refresh(): Result<Baseline> {
        if (refreshLock.isLocked) return Result.failure(IllegalStateException("Refresh already running"))
        return refreshLock.withLock {
            val s = settings.value
            if (!s.isConfigured) {
                status.value = status.value.copy(lastError = "Athlete id and API key not set")
                return@withLock Result.failure(IllegalStateException("Not configured"))
            }
            status.value = status.value.copy(refreshing = true)
            val now = System.currentTimeMillis()
            val result = runCatching {
                val api = IntervalsApi(SmartTransport { karoo }, s.athleteId, s.apiKey)
                BaselineFetcher(api).fetch(s, LocalDate.now(), now)
            }
            result.onSuccess { b ->
                baseline.value = b
                runCatching { cacheFile.writeText(IntervalsApi.json.encodeToString(Baseline.serializer(), b)) }
                dropLiveRideIfUploaded(b)
                status.value = Status(lastSuccessEpochMs = now)
                Log.i(TAG, "Baseline refreshed: ctl=${b.ctlYesterday} atl=${b.atlYesterday} cil=${b.cilYesterday}")
            }.onFailure { e ->
                status.value = status.value.copy(refreshing = false, lastError = e.message ?: e.javaClass.simpleName)
                Log.w(TAG, "Baseline refresh failed", e)
            }
            result
        }
    }

    /**
     * A finished ride keeps counting until it appears in Intervals; once an activity starting
     * within 15 minutes of it is in the baseline, the live copy is dropped so it isn't counted twice.
     */
    private fun dropLiveRideIfUploaded(b: Baseline) {
        val ride = live.value ?: return
        if (ride.recording) return
        val uploaded = ride.date != b.today || b.weekActivities.any { a ->
            a.startEpochMs != null && abs(a.startEpochMs!! - ride.startEpochMs) < 15 * 60_000
        }
        if (uploaded) live.value = null
    }
}
