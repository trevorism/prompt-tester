package com.trevorism.service

import com.trevorism.event.DefaultEventClient
import com.trevorism.event.EventClient
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.TestResult
import com.trevorism.model.TestRun
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Instant

/**
 * Publishes the real testResult once every immediate event has come back to /webhook. This runs on
 * the webhook request rather than a thread left behind by /test, because App Engine gives no CPU
 * guarantee outside a request.
 *
 * PUBLISH_GRACE_MILLIS exists to win a race, not to wait for anything: the testing service turns
 * the /test response into an "unverified" testResult event the moment /test returns, and that event
 * would overwrite a real result that got there first. Holding the publish until the run is 20s old
 * puts it comfortably behind, and stays well inside the 60s Pub/Sub ack deadline.
 */
@Singleton
class TestRunFinalizer {

    private static final Logger log = LoggerFactory.getLogger(TestRunFinalizer.class.name)
    private static final String TEST_RESULT_TOPIC = "testResult"

    long publishGraceMillis = 20000

    private final PromptEventTestService promptEventTestService
    private final ReceiptService receiptService
    private final EventClient<TestResult> eventClient

    TestRunFinalizer(PromptEventTestService promptEventTestService, ReceiptService receiptService,
                     @Named("promptTesterSecureHttpClient") SecureHttpClient secureHttpClient) {
        this.promptEventTestService = promptEventTestService
        this.receiptService = receiptService
        this.eventClient = new DefaultEventClient<TestResult>(secureHttpClient)
    }

    void finalizeIfComplete() {
        TestRun testRun = receiptService.readRun()
        if (!testRun || testRun.published) {
            return
        }

        List<Boolean> results = promptEventTestService.receiptStatus(testRun)
        if (!results.every { it }) {
            log.info("Run ${testRun.triggeredAt} still incomplete: ${[PromptEventTestService.IMMEDIATE_TOPICS, results].transpose()}")
            return
        }

        long triggeredAtMillis = Instant.parse(testRun.triggeredAt).toEpochMilli()
        int durationMillis = (int) (System.currentTimeMillis() - triggeredAtMillis)
        awaitPublishGrace(triggeredAtMillis)

        if (receiptService.readRun()?.published) {
            return
        }
        testRun.published = true
        receiptService.storeRun(testRun)

        TestResult testResult = new TestResult([
                service       : testRun.source,
                kind          : testRun.kind,
                success       : true,
                numberOfTests : results.size(),
                durationMillis: durationMillis
        ])
        eventClient.sendEvent(TEST_RESULT_TOPIC, testResult)
        log.info("Published a passing test result for run ${testRun.triggeredAt} in ${durationMillis}ms")
    }

    private void awaitPublishGrace(long triggeredAtMillis) {
        long remaining = triggeredAtMillis + publishGraceMillis - System.currentTimeMillis()
        if (remaining <= 0) {
            return
        }
        try {
            Thread.sleep(Math.min(remaining, publishGraceMillis))
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }
}
