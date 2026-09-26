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
