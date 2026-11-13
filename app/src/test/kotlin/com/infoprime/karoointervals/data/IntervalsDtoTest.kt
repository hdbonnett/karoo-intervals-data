package com.infoprime.karoointervals.data

import com.infoprime.karoointervals.ui.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** JSON shapes follow the Intervals.icu OpenAPI spec (field names as the API returns them). */
class IntervalsDtoTest {

    @Test
    fun `activity from the trimmed activities response`() {
        val body = """
            [{"id":"i98765","start_date_local":"2026-11-03T06:40:12","start_date":"2026-11-02T19:40:12Z",
              "type":"Ride","moving_time":5100,"distance":25012.4,"icu_training_load":29,
              "icu_intensity":47.3,"paired_event_id":150487425,"icu_ftp":175,"some_new_field":1}]
        """.trimIndent()
        val a = IntervalsApi.json.decodeFromString<List<ActivityDto>>(body).single().toCore()!!
        assertEquals("2026-11-03", a.date)
        assertEquals(5100, a.movingSecs)
        assertEquals(29.0, a.load)
        assertEquals(47.3, a.intensityPct!!, 1e-9)
        assertEquals(150487425L, a.pairedEventId)
        assertEquals(1_793_648_412_000L, a.startEpochMs)
    }

    @Test
    fun `activity with missing optional fields`() {
        val a = IntervalsApi.json.decodeFromString<List<ActivityDto>>(
            """[{"id":"i1","start_date_local":"2026-11-01T08:00:00","type":"WeightTraining","moving_time":1800}]""",
        ).single().toCore()!!
        assertEquals(0.0, a.load)
        assertNull(a.intensityPct)
        assertNull(a.pairedEventId)
    }

    @Test
    fun `planned workout from the events response`() {
        val body = """
            [{"id":150487431,"start_date_local":"2026-11-05T00:00:00","category":"WORKOUT","name":"Fatigue Test (short)",
              "type":"Ride","moving_time":2700,"icu_training_load":32,"icu_intensity":65.0,
              "workout_doc":{"steps":[]},"description":"- 10m ramp 50-65%"}]
        """.trimIndent()
        val w = IntervalsApi.json.decodeFromString<List<EventDto>>(body).single().toCore()!!
        assertEquals("2026-11-05", w.date)
        assertEquals(2700, w.movingSecs)
        assertEquals(32.0, w.load)
        assertEquals(65.0, w.intensityPct!!, 1e-9)
    }

    @Test
    fun `wellness rows`() {
        val rows = IntervalsApi.json.decodeFromString<List<WellnessDto>>(
            """[{"id":"2026-11-04","ctl":41.26,"atl":46.34},{"id":"2026-11-03"}]""",
        )
        assertEquals(41.26, rows[0].ctl!!, 1e-9)
        assertNull(rows[1].ctl)
    }

    @Test
    fun `durations format like the week card`() {
        assertEquals("8h43m", Format.duration(523 * 60.0))
        assertEquals("8h43m / 8h48m", Format.timeVsTarget(523 * 60.0, 528 * 60.0))
        assertEquals("0h05m", Format.duration(300.0))
    }

    @Test
    fun `week percentages round down like Intervals, distances convert units`() {
        assertEquals("91%", Format.pctDown(435.0 / 473.0 * 100))
        assertEquals("99%", Format.pctDown(523.0 / 528.0 * 100))
        assertEquals("8h43/8h48", Format.timeVsTargetShort(523 * 60.0, 528 * 60.0))
        assertEquals("435/473", Format.loadVsTargetShort(435.0, 473.0))
        assertEquals("25.4 km", Format.distance(25_412.0, imperial = false))
        assertEquals("15.8 mi", Format.distance(25_412.0, imperial = true))
    }
}
