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
import java.time.temporal.ChronoUnit

/**
 * Publishes the testResult for a run, the same way a unit or cucumber run reports itself: the
 * dispatcher does not wait for a verdict, the runner emits one when it has it.
 *
 * finalizeIfComplete runs on the webhook request rather than a thread left behind by /test, because
 * App Engine gives no CPU guarantee outside a request. It stays short on purpose -- it is holding a
 * Pub/Sub push open, and overrunning ackDeadlineSeconds would redeliver the very events being counted.
 *
 * A run whose events never all came back publishes nothing, so closeOutStaleRun reports the previous
 * run as failed at the start of the next one. Without it a broken prompt pipeline would leave the
 * suite sitting on its last green result.
 *
 * Both paths claim the run before reporting it, so a run is reported once whichever gets there
 * first. Reading a "published" flag could not do this: the pushes that race here all read it before
 * any of them had written it back.
 */
@Singleton
class TestRunFinalizer {

    private static final Logger log = LoggerFactory.getLogger(TestRunFinalizer.class.name)
    private static final String TEST_RESULT_TOPIC = "testResult"

    long staleAfterMillis = 300000

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
        if (!testRun) {
            return
        }

        List<Boolean> results = promptEventTestService.receiptStatus(testRun)
        if (!results.every { it }) {
            log.info("Run ${testRun.triggeredAt} still incomplete: ${[PromptEventTestService.IMMEDIATE_TOPICS, results].transpose()}")
            return
        }

        Instant triggeredAt = Instant.parse(testRun.triggeredAt)
        int durationMillis = (int) (System.currentTimeMillis() - triggeredAt.toEpochMilli())
        if (!receiptService.claimRun(testRun)) {
            log.info("Run ${testRun.triggeredAt} was already reported by another webhook")
            return
        }
        publish(testRun, true, results.size(), durationMillis, Instant.now())
        log.info("Published a passing test result for run ${testRun.triggeredAt} in ${durationMillis}ms")
    }

    void closeOutStaleRun() {
        TestRun testRun = receiptService.readRun()
        if (!testRun) {
            return
        }

        Instant triggeredAt = Instant.parse(testRun.triggeredAt)
        if (triggeredAt.isAfter(Instant.now().minusMillis(staleAfterMillis))) {
            return
        }
        if (!receiptService.claimRun(testRun)) {
            return
        }

        log.warn("Run ${testRun.triggeredAt} never received every event; reporting it as a failure")
        publish(testRun, false, PromptEventTestService.IMMEDIATE_TOPICS.size(), 0, triggeredAt)
    }

    private void publish(TestRun testRun, boolean success, int numberOfTests, int durationMillis, Instant date) {
        eventClient.sendEvent(TEST_RESULT_TOPIC, new TestResult([
                service       : testRun.source,
                kind          : testRun.kind,
                success       : success,
                numberOfTests : numberOfTests,
                durationMillis: durationMillis,
                date          : date.truncatedTo(ChronoUnit.SECONDS).toString()
        ]))
    }
}
