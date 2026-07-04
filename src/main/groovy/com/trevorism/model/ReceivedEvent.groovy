package com.trevorism.model

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A single event payload delivered by the event service to one of the /webhook/{topic} endpoints.
 */
class ReceivedEvent {
    String topic
    String payload
    String receivedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
}
