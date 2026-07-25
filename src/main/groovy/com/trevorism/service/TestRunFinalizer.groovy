package com.trevorism.service

import com.google.gson.Gson
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
 * The publish has to be sequenced behind the testing service, which turns the /test response into
 * an "unverified" testResult the moment /test returns and would otherwise overwrite a real result
 * that got there first. Rather than guess how long that takes, wait until the suite record actually
 * shows the unverified run, then publish. event-tester reads the same object the same way.
 */
@Singleton
class TestRunFinalizer {

    private static final Logger log = LoggerFactory.getLogger(TestRunFinalizer.class.name)
    private static final String TEST_RESULT_TOPIC = "testResult"
    private static final String SUITE_URL = "https://datastore.data.trevorism.com/object/testsuite"

    long interimTimeoutMillis = 45000
    long interimPollMillis = 3000
    long settleMillis = 2000
    long fallbackGraceMillis = 15000

    private final PromptEventTestService promptEventTestService
    private final ReceiptService receiptService
    private final SecureHttpClient secureHttpClient
    private final EventClient<TestResult> eventClient
    private final Gson gson = new Gson()

    TestRunFinalizer(PromptEventTestService promptEventTestService, ReceiptService receiptService,
                     @Named("promptTesterSecureHttpClient") SecureHttpClient secureHttpClient) {
        this.promptEventTestService = promptEventTestService
        this.receiptService = receiptService
        this.secureHttpClient = secureHttpClient
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

        Instant triggeredAt = Instant.parse(testRun.triggeredAt)
        int durationMillis = (int) (System.currentTimeMillis() - triggeredAt.toEpochMilli())
        awaitInterimResult(testRun.suiteId, triggeredAt)

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

    private void awaitInterimResult(String suiteId, Instant triggeredAt) {
        if (!suiteId) {
            log.warn("Run ${triggeredAt} has no suite id to watch; falling back to a fixed grace")
            sleepFor(fallbackGraceMillis)
            return
        }
        long deadline = System.currentTimeMillis() + interimTimeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (interimResultRecorded(suiteId, triggeredAt)) {
                sleepFor(settleMillis)
                return
            }
            sleepFor(interimPollMillis)
        }
        log.warn("The unverified result for run ${triggeredAt} never appeared on suite ${suiteId}; publishing anyway")
    }

    private boolean interimResultRecorded(String suiteId, Instant triggeredAt) {
        try {
            String response = secureHttpClient.get("${SUITE_URL}/${suiteId}".toString())
            String lastRunDate = gson.fromJson(response, Map)?.lastrundate
            return lastRunDate && !Instant.parse(lastRunDate).isBefore(triggeredAt)
        } catch (Exception e) {
            log.debug("Could not read suite ${suiteId}: ${e.message}")
            return false
        }
    }

    private static void sleepFor(long millis) {
        if (millis <= 0) {
            return
        }
        try {
            Thread.sleep(millis)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }
}
