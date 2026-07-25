package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.http.util.InvalidRequestException
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.TestRun
import org.apache.hc.client5.http.HttpResponseException
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assertions.assertFalse

class TestRunFinalizerTest {

    private static final Gson gson = new Gson()

    private final Map<String, String> objects = [:]
    private final List<Map<String, String>> sentEvents = []
    private final Set<String> claims = [] as Set

    @Test
    void testCompleteRunPublishesAPassingResult() {
        Instant triggeredAt = Instant.now().minusSeconds(5)
        storeRun(triggeredAt)
        PromptEventTestService.IMMEDIATE_TOPICS.each { storeReceipt(it, triggeredAt.plusSeconds(1), PromptEventTestService.MARKER) }

        finalizer().finalizeIfComplete()

        assertEquals(1, sentEvents.size())
        assertTrue(sentEvents.first().url.endsWith("/event/testResult"))
        Map event = publishedEvent()
        assertTrue(event.success as boolean)
        assertEquals("prompt-tester", event.service)
        assertEquals("web", event.kind)
        assertEquals(4, (event.numberOfTests as Number).intValue())
        assertTrue(claims.contains(triggeredAt.toString()))
    }

    @Test
    void testEveryWebhookOfACompleteRunPublishesOnlyOneResult() {
        Instant triggeredAt = Instant.now().minusSeconds(5)
        storeRun(triggeredAt)
        PromptEventTestService.IMMEDIATE_TOPICS.each { storeReceipt(it, triggeredAt.plusSeconds(1), PromptEventTestService.MARKER) }

        TestRunFinalizer finalizer = finalizer()
        PromptEventTestService.IMMEDIATE_TOPICS.each { finalizer.finalizeIfComplete() }

        assertEquals(1, sentEvents.size())
    }

    @Test
    void testAlreadyClaimedRunIsNotPublishedAgain() {
        Instant triggeredAt = Instant.now().minusSeconds(5)
        storeRun(triggeredAt)
        claims << triggeredAt.toString()
        PromptEventTestService.IMMEDIATE_TOPICS.each { storeReceipt(it, triggeredAt.plusSeconds(1), PromptEventTestService.MARKER) }

        finalizer().finalizeIfComplete()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    void testMissingReceiptHoldsThePublish() {
        Instant triggeredAt = Instant.now().minusSeconds(5)
        storeRun(triggeredAt)
        PromptEventTestService.IMMEDIATE_TOPICS.tail().each { storeReceipt(it, triggeredAt.plusSeconds(1), PromptEventTestService.MARKER) }

        finalizer().finalizeIfComplete()

        assertTrue(sentEvents.isEmpty())
        assertTrue(claims.isEmpty())
    }

    @Test
    void testReceiptsWithoutTheMarkerHoldThePublish() {
        Instant triggeredAt = Instant.now().minusSeconds(5)
        storeRun(triggeredAt)
        PromptEventTestService.IMMEDIATE_TOPICS.each { storeReceipt(it, triggeredAt.plusSeconds(1), "some other question") }

        finalizer().finalizeIfComplete()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    void testReceiptsOlderThanTheTriggerHoldThePublish() {
        Instant triggeredAt = Instant.now().minusSeconds(5)
        storeRun(triggeredAt)
        PromptEventTestService.IMMEDIATE_TOPICS.each { storeReceipt(it, triggeredAt.minusSeconds(1), PromptEventTestService.MARKER) }

        finalizer().finalizeIfComplete()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    void testNoRecordedRunPublishesNothing() {
        finalizer().finalizeIfComplete()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    void testStaleUnreportedRunIsReportedAsAFailure() {
        Instant triggeredAt = Instant.now().minusSeconds(3600)
        storeRun(triggeredAt)

        finalizer().closeOutStaleRun()

        assertEquals(1, sentEvents.size())
        Map event = publishedEvent()
        assertFalse(event.success as boolean)
        assertEquals("prompt-tester", event.service)
        assertEquals(4, (event.numberOfTests as Number).intValue())
        assertTrue(claims.contains(triggeredAt.toString()))
    }

    @Test
    void testStaleFailureIsDatedWhenTheRunActuallyRan() {
        Instant triggeredAt = Instant.now().minusSeconds(3600)
        storeRun(triggeredAt)

        finalizer().closeOutStaleRun()

        assertEquals(triggeredAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), publishedEvent().date)
    }

    @Test
    void testRunStillInFlightIsNotClosedOut() {
        storeRun(Instant.now().minusSeconds(5))

        finalizer().closeOutStaleRun()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    void testAlreadyClaimedRunIsNotClosedOut() {
        Instant triggeredAt = Instant.now().minusSeconds(3600)
        storeRun(triggeredAt)
        claims << triggeredAt.toString()

        finalizer().closeOutStaleRun()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    void testARunReportedAsCompleteIsNotAlsoClosedOutAsStale() {
        Instant triggeredAt = Instant.now().minusSeconds(3600)
        storeRun(triggeredAt)
        PromptEventTestService.IMMEDIATE_TOPICS.each { storeReceipt(it, triggeredAt.plusSeconds(1), PromptEventTestService.MARKER) }

        TestRunFinalizer finalizer = finalizer()
        finalizer.finalizeIfComplete()
        finalizer.closeOutStaleRun()

        assertEquals(1, sentEvents.size())
        assertTrue(publishedEvent().success as boolean)
    }

    private Map publishedEvent() {
        return gson.fromJson(sentEvents.first().body, Map)
    }

    private TestRunFinalizer finalizer() {
        SecureHttpClient client = httpClient()
        ReceiptService receiptService = new ReceiptService(client)
        return new TestRunFinalizer(new PromptEventTestService(client, receiptService), receiptService, client)
    }

    private SecureHttpClient httpClient() {
        [
                get   : { String url -> objects[idOf(url)] },
                post  : { String url, String body ->
                    if (url.contains("/event/")) {
                        sentEvents << [url: url, body: body]
                        return "{}"
                    }
                    String id = gson.fromJson(body, Map).id as String
                    if (url.endsWith("/prompt-run-claim")) {
                        if (claims.contains(id)) {
                            throw conflict()
                        }
                        claims << id
                        return body
                    }
                    objects[id] = body
                    return body
                },
                put   : { String url, String body ->
                    if (url.endsWith("/prompt-run-claim")) {
                        claims.clear()
                        return "0"
                    }
                    String id = idOf(url)
                    if (!objects.containsKey(id)) {
                        throw notFound()
                    }
                    objects[id] = body
                    return body
                },
                delete: { String url -> objects.remove(idOf(url)); return "" }
        ] as SecureHttpClient
    }

    private static InvalidRequestException conflict() {
        return new InvalidRequestException(new HttpResponseException(409, "Conflict"), 409)
    }

    private static InvalidRequestException notFound() {
        return new InvalidRequestException(new HttpResponseException(404, "Not Found"), 404)
    }

    private void storeRun(Instant triggeredAt) {
        objects["run"] = gson.toJson(new TestRun(source: "prompt-tester", kind: "web",
                triggeredAt: triggeredAt.toString()))
    }

    private void storeReceipt(String topic, Instant timestamp, String text) {
        objects[topic] = gson.toJson([id     : topic, topic: topic, timestamp: timestamp.toString(),
                                      payload: gson.toJson([questionId: "123", text: "${text} plain question".toString()])])
    }

    private static String idOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1)
    }
}
