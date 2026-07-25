package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.TestRun
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assertions.assertFalse

class TestRunFinalizerTest {

    private static final Gson gson = new Gson()

    private final Map<String, String> objects = [:]
    private final List<Map<String, String>> sentEvents = []

    @Test
    void testCompleteRunPublishesAPassingResult() {
        Instant triggeredAt = Instant.now().minusSeconds(5)
        storeRun(triggeredAt, false)
        PromptEventTestService.IMMEDIATE_TOPICS.each { storeReceipt(it, triggeredAt.plusSeconds(1), "123") }

        finalizer().finalizeIfComplete()

        assertEquals(1, sentEvents.size())
        assertTrue(sentEvents.first().url.endsWith("/event/testResult"))
        Map event = gson.fromJson(sentEvents.first().body, Map)
        assertTrue(event.success as boolean)
        assertEquals("prompt-tester", event.service)
        assertEquals("web", event.kind)
        assertEquals(4, (event.numberOfTests as Number).intValue())
        assertTrue(gson.fromJson(objects["run"], TestRun).published)
    }

    @Test
    void testAlreadyPublishedRunIsNotPublishedAgain() {
        Instant triggeredAt = Instant.now().minusSeconds(5)
        storeRun(triggeredAt, true)
        PromptEventTestService.IMMEDIATE_TOPICS.each { storeReceipt(it, triggeredAt.plusSeconds(1), "123") }

        finalizer().finalizeIfComplete()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    void testMissingReceiptHoldsThePublish() {
        Instant triggeredAt = Instant.now().minusSeconds(5)
        storeRun(triggeredAt, false)
        PromptEventTestService.IMMEDIATE_TOPICS.tail().each { storeReceipt(it, triggeredAt.plusSeconds(1), "123") }

        finalizer().finalizeIfComplete()

        assertTrue(sentEvents.isEmpty())
        assertFalse(gson.fromJson(objects["run"], TestRun).published)
    }

    @Test
    void testReceiptsFromAnotherRunHoldThePublish() {
        Instant triggeredAt = Instant.now().minusSeconds(5)
        storeRun(triggeredAt, false)
        PromptEventTestService.IMMEDIATE_TOPICS.each { storeReceipt(it, triggeredAt.plusSeconds(1), "999") }

        finalizer().finalizeIfComplete()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    void testNoRecordedRunPublishesNothing() {
        finalizer().finalizeIfComplete()

        assertTrue(sentEvents.isEmpty())
    }

    private TestRunFinalizer finalizer() {
        SecureHttpClient client = httpClient()
        ReceiptService receiptService = new ReceiptService(client)
        TestRunFinalizer testRunFinalizer = new TestRunFinalizer(
                new PromptEventTestService(client, receiptService), receiptService, client)
        testRunFinalizer.publishGraceMillis = 0
        return testRunFinalizer
    }

    private SecureHttpClient httpClient() {
        [
                get   : { String url -> objects[idOf(url)] },
                post  : { String url, String body ->
                    if (url.contains("/event/")) {
                        sentEvents << [url: url, body: body]
                        return "{}"
                    }
                    objects[gson.fromJson(body, Map).id as String] = body
                    return body
                },
                delete: { String url -> objects.remove(idOf(url)); return "" }
        ] as SecureHttpClient
    }

    private void storeRun(Instant triggeredAt, boolean published) {
        objects["run"] = gson.toJson(new TestRun(source: "prompt-tester", kind: "web",
                triggeredAt: triggeredAt.toString(), questionIds: ["123"], published: published))
    }

    private void storeReceipt(String topic, Instant timestamp, String questionId) {
        objects[topic] = gson.toJson([id       : topic, topic: topic, timestamp: timestamp.toString(),
                                      payload  : gson.toJson([questionId: questionId])])
    }

    private static String idOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1)
    }
}
