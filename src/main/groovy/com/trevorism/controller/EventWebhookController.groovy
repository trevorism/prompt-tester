package com.trevorism.controller

import com.trevorism.service.EventStore
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Public landing point for events pushed by the event service (event.data.trevorism.com).
 *
 * Deliberately UNSECURED (no @Secure): the event service posts raw event JSON here with no
 * trevorism bearer token. Each prompt event topic is subscribed with a url of
 * https://prompt-tester.testing.trevorism.com/webhook/{topic}, so the topic arrives as a path var.
 */
@Controller("/webhook")
class EventWebhookController {

    private static final Logger log = LoggerFactory.getLogger(EventWebhookController.class.name)

    private final EventStore eventStore

    EventWebhookController(EventStore eventStore) {
        this.eventStore = eventStore
    }

    @Tag(name = "Webhook Operations")
    @Operation(summary = "Receives a pushed event for the given topic (public)")
    @Post(value = "/{topic}", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    HttpResponse<Map> receive(String topic, @Body String payload) {
        log.info("Received webhook for topic '{}' ({} bytes)", topic, payload?.length() ?: 0)
        eventStore.record(topic, payload ?: "")
        return HttpResponse.ok([received: true, topic: topic])
    }
}
