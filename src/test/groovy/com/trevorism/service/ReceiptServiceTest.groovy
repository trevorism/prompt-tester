package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.TestRun
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class ReceiptServiceTest {

    private static final Gson gson = new Gson()
    private static final Instant TRIGGERED_AT = Instant.parse("2026-07-25T14:00:00Z")
    private static final String MARKER = "[prompt-tester]"

    @Test
    void testReceiptFromThisRunIsAccepted() {
        ReceiptService service = serviceReturning(receipt(TRIGGERED_AT.plusSeconds(5), "${MARKER} plain question"))
        assertTrue(service.receivedSince("questionAsked", TRIGGERED_AT, MARKER))
    }

    @Test
    void testReceiptFromUnrelatedTrafficIsRejected() {
        ReceiptService service = serviceReturning(receipt(TRIGGERED_AT.plusSeconds(1), "a real question from a user"))
        assertFalse(service.receivedSince("approvalRequested", TRIGGERED_AT, MARKER))
    }

    @Test
    void testReceiptOlderThanTheTriggerIsRejected() {
        ReceiptService service = serviceReturning(receipt(TRIGGERED_AT.minusSeconds(5), "${MARKER} plain question"))
        assertFalse(service.receivedSince("questionAsked", TRIGGERED_AT, MARKER))
    }

    @Test
    void testReceiptWithAnEmptyPayloadIsRejected() {
        String payload = gson.toJson([id: "questionAsked", topic: "questionAsked",
                                      timestamp: TRIGGERED_AT.plusSeconds(5).toString(), payload: "{}"])
        assertFalse(serviceReturning(payload).receivedSince("questionAsked", TRIGGERED_AT, MARKER))
    }

    @Test
    void testNoReceiptIsRejected() {
        assertFalse(serviceReturning(null).receivedSince("questionAsked", TRIGGERED_AT, MARKER))
    }

    @Test
    void testTransportFailureIsRejected() {
        SecureHttpClient client = [get: { String url -> throw new RuntimeException("not found") }] as SecureHttpClient
        assertFalse(new ReceiptService(client).receivedSince("questionAsked", TRIGGERED_AT, MARKER))
    }

    @Test
    void testRunRecordRoundTrips() {
        Map<String, String> objects = [:]
        SecureHttpClient client = [
                get   : { String url -> objects[url.substring(url.lastIndexOf('/') + 1)] },
                post  : { String url, String body -> objects[gson.fromJson(body, Map).id as String] = body },
                delete: { String url -> objects.remove(url.substring(url.lastIndexOf('/') + 1)) }
        ] as SecureHttpClient
        ReceiptService service = new ReceiptService(client)

        service.storeRun(new TestRun(source: "prompt-tester", kind: "web", suiteId: "5958628024516608",
                triggeredAt: TRIGGERED_AT.toString()))

        TestRun stored = service.readRun()
        assertEquals("prompt-tester", stored.source)
        assertEquals("5958628024516608", stored.suiteId)
        assertEquals(TRIGGERED_AT.toString(), stored.triggeredAt)
        assertFalse(stored.published)
    }

    @Test
    void testNoRunRecordReadsAsNull() {
        assertNull(serviceReturning(null).readRun())
    }

    private static ReceiptService serviceReturning(String response) {
        new ReceiptService([get: { String url -> response }] as SecureHttpClient)
    }

    private static String receipt(Instant timestamp, String text) {
        gson.toJson([id     : "questionAsked", topic: "questionAsked", timestamp: timestamp.toString(),
                     payload: gson.toJson([questionId: "123", text: text])])
    }
}
