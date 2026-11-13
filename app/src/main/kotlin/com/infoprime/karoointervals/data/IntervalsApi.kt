package com.infoprime.karoointervals.data

import android.util.Base64
import com.infoprime.karoointervals.core.CompletedActivity
import com.infoprime.karoointervals.core.Intensity
import com.infoprime.karoointervals.core.PlannedWorkout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

class IntervalsException(message: String) : Exception(message)

/**
 * The few Intervals.icu API calls the extension needs. Responses are trimmed with `fields=`
 * where the API allows it, to stay inside Karoo's 100 KB response limit.
 */
class IntervalsApi(
    private val transport: HttpTransport,
    private val athleteId: String,
    apiKey: String,
) {
    private val headers = mapOf(
        "Authorization" to "Basic " + Base64.encodeToString("API_KEY:$apiKey".toByteArray(), Base64.NO_WRAP),
        "Accept" to "application/json",
    )
    private val base = "https://intervals.icu/api/v1/athlete/$athleteId"

    suspend fun wellness(oldest: LocalDate, newest: LocalDate): List<WellnessDto> =
        get("$base/wellness?oldest=$oldest&newest=$newest&fields=id,ctl,atl")

    suspend fun activities(oldest: LocalDate, newest: LocalDate): List<ActivityDto> =
        get("$base/activities?oldest=$oldest&newest=${newest}T23:59:59&fields=$ACTIVITY_FIELDS")

    /** Planned workouts. Events can't be trimmed with `fields`, so fall back to a day at a time if a week is too big. */
    suspend fun plannedWorkouts(oldest: LocalDate, newest: LocalDate): List<EventDto> {
        val url = "$base/events?oldest=$oldest&newest=$newest&category=WORKOUT"
        return try {
            get(url)
        } catch (e: IntervalsException) {
            if (e.message?.startsWith("HTTP 4") == true) throw e
            generateSequence(oldest) { it.plusDays(1) }.takeWhile { !it.isAfter(newest) }
                .toList()
                .flatMap { day -> get<List<EventDto>>("$base/events?oldest=$day&newest=$day&category=WORKOUT") }
        }
    }

    private suspend inline fun <reified T> get(url: String): T {
        val result = transport.get(url, headers)
        if (!result.ok) {
            val hint = when (result.status) {
                401, 403 -> " (check athlete id and API key)"
                0 -> " (no network)"
                else -> ""
            }
            throw IntervalsException("HTTP ${result.status}$hint ${result.error.orEmpty()}".trim())
        }
        return json.decodeFromString(result.body!!)
    }

    companion object {
        private const val ACTIVITY_FIELDS =
            "id,start_date_local,start_date,type,moving_time,distance,icu_training_load,icu_intensity,paired_event_id,icu_ftp"

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}

@Serializable
data class WellnessDto(
    val id: String,
    val ctl: Double? = null,
    val atl: Double? = null,
)

@Serializable
data class ActivityDto(
    val id: String,
    @SerialName("start_date_local") val startDateLocal: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    val type: String? = null,
    @SerialName("moving_time") val movingTime: Int? = null,
    val distance: Double? = null,
    @SerialName("icu_training_load") val load: Double? = null,
    @SerialName("icu_intensity") val intensity: Double? = null,
    @SerialName("paired_event_id") val pairedEventId: Long? = null,
    @SerialName("icu_ftp") val ftp: Int? = null,
) {
    fun toCore(): CompletedActivity? {
        val date = startDateLocal?.take(10) ?: return null
        return CompletedActivity(
            id = id,
            date = date,
            startEpochMs = startDate?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            type = type,
            movingSecs = movingTime ?: 0,
            distanceM = distance ?: 0.0,
            load = load ?: 0.0,
            intensityPct = Intensity.normalisePct(intensity),
            pairedEventId = pairedEventId,
        )
    }
}

@Serializable
data class EventDto(
    val id: Long,
    @SerialName("start_date_local") val startDateLocal: String? = null,
    val category: String? = null,
    val name: String? = null,
    val type: String? = null,
    @SerialName("moving_time") val movingTime: Int? = null,
    @SerialName("icu_training_load") val load: Double? = null,
    @SerialName("icu_intensity") val intensity: Double? = null,
) {
    fun toCore(): PlannedWorkout? {
        val date = startDateLocal?.take(10) ?: return null
        return PlannedWorkout(
            id = id,
            date = date,
            name = name,
            movingSecs = movingTime ?: 0,
            load = load ?: 0.0,
            intensityPct = Intensity.normalisePct(intensity),
        )
    }
}
