package com.trevorism.controller

import com.trevorism.service.ReceiptService
import com.trevorism.service.TestRunFinalizer
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Public landing point for events pushed by the event service (Google Pub/Sub push).
 *
 * Deliberately UNSECURED (no @Secure): Pub/Sub posts raw event JSON here with no trevorism bearer
 * token. Each prompt topic is subscribed with a url of .../webhook/{topic}, so the topic arrives as
 * a path var. Receipts are recorded in the shared memory service (ReceiptService) so a push handled
 * on one GAE instance is visible to the /test call on another.
 *
 * NOTE: produces = application/json is REQUIRED. Pub/Sub push sends Accept: application/json; a
 * text/plain-only response yields HTTP 406 and Pub/Sub silently retries then dead-letters.
 *
 * NOTE: @ExecuteOn(BLOCKING) is REQUIRED. Storing a receipt and finalizing a run are blocking HTTP
 * calls; on the event loop they starve the pushes this app is waiting for.
 */
@Controller("/webhook")
@ExecuteOn(TaskExecutors.BLOCKING)
class EventWebhookController {

    private static final Logger log = LoggerFactory.getLogger(EventWebhookController.class.name)

    private final ReceiptService receiptService
    private final TestRunFinalizer testRunFinalizer

    EventWebhookController(ReceiptService receiptService, TestRunFinalizer testRunFinalizer) {
        this.receiptService = receiptService
        this.testRunFinalizer = testRunFinalizer
    }

    @Tag(name = "Webhook Operations")
    @Operation(summary = "Receives a pushed event for the given topic (public)")
    @Post(value = "/{topic}", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    HttpResponse<Map> receive(String topic, @Body String payload) {
        log.info("Received webhook for topic '{}' ({} bytes)", topic, payload?.length() ?: 0)
        receiptService.store(topic, payload ?: "")
        try {
            testRunFinalizer.finalizeIfComplete()
        } catch (Exception e) {
            log.warn("Could not finalize the test run", e)
        }
        return HttpResponse.ok([received: true, topic: topic])
    }
}
