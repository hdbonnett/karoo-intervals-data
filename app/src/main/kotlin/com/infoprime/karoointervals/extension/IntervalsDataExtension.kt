package com.infoprime.karoointervals.extension

import android.util.Log
import com.infoprime.karoointervals.core.LiveRide
import com.infoprime.karoointervals.data.Engine
import com.infoprime.karoointervals.data.TAG
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.time.LocalDate

class IntervalsDataExtension : KarooExtension(EXTENSION_ID, "1") {

    private val karooSystem by lazy { KarooSystemService(this) }
    private var serviceJob: Job? = null

    override val types by lazy { DataFields.all(EXTENSION_ID) }

    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        karooSystem.connect { connected ->
            Log.i(TAG, "Karoo system connected=$connected")
            Engine.karoo = if (connected) karooSystem else null
            if (connected && Engine.baselineStale(maxAgeMs = 60 * 60_000L)) {
                Engine.scope.launch { Engine.refresh() }
            }
        }
        serviceJob = Engine.scope.launch {
            launch { trackLiveRide() }
            launch { refreshPeriodically() }
            launch { trackWorkout() }
            launch {
                karooSystem.consumerFlow<UserProfile>().collect {
                    Engine.imperialUnits.value =
                        it.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
                }
            }
        }
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        serviceJob = null
        Engine.karoo = null
        karooSystem.disconnect()
        super.onDestroy()
    }

    /** Mirrors the ride being recorded into [Engine.live]. */
    private suspend fun trackLiveRide() {
        var rideStartMs: Long? = null
        var rideDate: String? = null

        combine(
            karooSystem.consumerFlow<RideState>(),
            karooSystem.valueFlow(DataType.Type.ELAPSED_TIME).onStart { emit(null) },
            karooSystem.valueFlow(DataType.Type.DISTANCE).onStart { emit(null) },
            karooSystem.valueFlow(DataType.Type.NORMALIZED_POWER).onStart { emit(null) },
        ) { state, elapsedMs, distanceM, np -> Snapshot(state, elapsedMs ?: 0.0, distanceM ?: 0.0, np) }
            .collect { s ->
                when (s.state) {
                    is RideState.Recording, is RideState.Paused -> {
                        if (rideStartMs == null) {
                            // Also covers the service starting mid-ride.
                            rideStartMs = System.currentTimeMillis() - s.elapsedMs.toLong()
                            rideDate = LocalDate.now().toString()
                            Log.i(TAG, "Ride started $rideDate")
                            if (Engine.baselineStale(maxAgeMs = 15 * 60_000L)) {
                                Engine.scope.launch { Engine.refresh() }
                            }
                        }
                        Engine.live.value = LiveRide(
                            startEpochMs = rideStartMs!!,
                            date = rideDate!!,
                            movingSecs = s.elapsedMs / 1000.0,
                            distanceM = s.distanceM,
                            normalizedPowerW = s.np?.takeIf { it > 0 },
                            recording = true,
                        )
                    }
                    is RideState.Idle -> if (rideStartMs != null) {
                        // Ride ended: keep counting it until it shows up in Intervals.
                        Log.i(TAG, "Ride ended")
                        Engine.live.value = Engine.live.value?.copy(recording = false)
                        rideStartMs = null
                        rideDate = null
                    }
                }
            }
    }

    /**
     * Karoo publishes "workout time remaining" only while a workout is loaded and running.
     * Time values from Karoo are in milliseconds.
     */
    private suspend fun trackWorkout() {
        karooSystem.streamDataFlow(DataType.Type.WORKOUT_REMAINING_TOTAL_DURATION).collect { state ->
            val values = (state as? StreamState.Streaming)?.dataPoint?.values
            val remainingMs = values?.get(DataType.Field.WORKOUT_REMAINING_TIME)
            if (values == null || remainingMs == null) {
                if (Engine.karooWorkoutLengthSecs.value != null) Log.i(TAG, "Workout ended ($state)")
                Engine.karooWorkoutLengthSecs.value = null
                return@collect
            }
            val remainingSecs = remainingMs / 1000.0
            val previous = Engine.karooWorkoutLengthSecs.value
            if (previous == null) {
                Log.i(TAG, "Workout running: remaining ${remainingSecs}s, state ${values[DataType.Field.WORKOUT_STATE]}")
            }
            Engine.karooWorkoutLengthSecs.value = maxOf(previous ?: 0.0, remainingSecs)
        }
    }

    private suspend fun refreshPeriodically() {
        while (true) {
            delay(30 * 60_000L)
            val ride = Engine.live.value
            val recording = ride?.recording == true
            val waitingForUpload = ride != null && !recording
            if (!recording && (waitingForUpload || Engine.baselineStale(maxAgeMs = 3 * 60 * 60_000L))) {
                Engine.refresh()
            }
        }
    }

    private data class Snapshot(val state: RideState, val elapsedMs: Double, val distanceM: Double, val np: Double?)

    companion object {
        const val EXTENSION_ID = "intervals-data"
    }
}
