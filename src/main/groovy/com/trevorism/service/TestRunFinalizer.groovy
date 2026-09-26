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
