package com.trevorism.controller

import com.trevorism.model.ReceivedEvent
import com.trevorism.secure.Roles
import com.trevorism.secure.Secure
import com.trevorism.service.EventStore
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

/**
 * Inspect / clear the events recorded by the webhook receiver. Useful for validating the
 * scheduler-driven due-date events out-of-band (trigger via /test with keep=true, then poll here
 * a few minutes later).
 */
@Controller("/events")
class EventInspectionController {

    private final EventStore eventStore

    EventInspectionController(EventStore eventStore) {
        this.eventStore = eventStore
    }

    @Tag(name = "Webhook Operations")
    @Operation(summary = "List all received events grouped by topic **Secure")
    @Get(produces = MediaType.APPLICATION_JSON)
    @Secure(Roles.USER)
    Map inspect() {
        [counts: eventStore.counts(), events: eventStore.listAll()]
    }

    @Tag(name = "Webhook Operations")
    @Operation(summary = "List received events for a single topic **Secure")
    @Get(value = "/{topic}", produces = MediaType.APPLICATION_JSON)
    @Secure(Roles.USER)
    List<ReceivedEvent> inspectTopic(String topic) {
        eventStore.listByTopic(topic)
    }

    @Tag(name = "Webhook Operations")
    @Operation(summary = "Clear all recorded events **Secure")
    @Delete(produces = MediaType.APPLICATION_JSON)
    @Secure(Roles.USER)
    Map clear() {
        eventStore.clear()
        [cleared: true]
    }
}
