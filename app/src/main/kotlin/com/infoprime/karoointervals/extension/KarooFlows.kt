package com.infoprime.karoointervals.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/** A Karoo data stream as a Flow (same pattern as the karoo-ext sample app). */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val consumerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
        trySend(event.state)
    }
    awaitClose { removeConsumer(consumerId) }
}

/** The stream's single value, or null while the sensor or value is unavailable. */
fun KarooSystemService.valueFlow(dataTypeId: String): Flow<Double?> =
    streamDataFlow(dataTypeId).map { (it as? StreamState.Streaming)?.dataPoint?.singleValue }

inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val consumerId = addConsumer<T> { trySend(it) }
    awaitClose { removeConsumer(consumerId) }
}
