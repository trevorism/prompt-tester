package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.http.util.InvalidRequestException
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.TestRun
import org.apache.hc.client5.http.HttpResponseException
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
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
        ReceiptService service = new ReceiptService(objectStore(objects))

        service.storeRun(new TestRun(source: "prompt-tester", kind: "web",
                triggeredAt: TRIGGERED_AT.toString()))

        TestRun stored = service.readRun()
        assertEquals("prompt-tester", stored.source)
        assertEquals("web", stored.kind)
        assertEquals(TRIGGERED_AT.toString(), stored.triggeredAt)
    }

    @Test
    void testStoringTheSameRecordTwiceReplacesIt() {
        Map<String, String> objects = [:]
        ReceiptService service = new ReceiptService(objectStore(objects))

        service.storeRun(new TestRun(source: "prompt-tester", kind: "web", triggeredAt: TRIGGERED_AT.toString()))
        service.storeRun(new TestRun(source: "prompt-tester", kind: "web",
                triggeredAt: TRIGGERED_AT.plusSeconds(30).toString()))

        assertEquals(TRIGGERED_AT.plusSeconds(30).toString(), service.readRun().triggeredAt)
    }

    @Test
    void testAConcurrentCreateDoesNotFailTheStore() {
        Map<String, String> objects = [:]
        SecureHttpClient client = [
                put : { String url, String body ->
                    if (!objects.containsKey(idOf(url))) {
                        throw status(404)
                    }
                    objects[idOf(url)] = body
                },
                post: { String url, String body ->
                    objects[gson.fromJson(body, Map).id as String] = "written by the other caller"
                    throw status(409)
                }
        ] as SecureHttpClient

        new ReceiptService(client).store("questionAsked", "a payload")

        assertTrue(objects["questionAsked"].contains("a payload"))
    }

    @Test
    void testTheFirstClaimWinsAndTheRestLose() {
        Set<String> claims = [] as Set
        SecureHttpClient client = [post: { String url, String body ->
            String id = gson.fromJson(body, Map).id as String
            if (!claims.add(id)) {
                throw status(409)
            }
            return body
        }] as SecureHttpClient
        ReceiptService service = new ReceiptService(client)
        TestRun testRun = new TestRun(triggeredAt: TRIGGERED_AT.toString())

        assertTrue(service.claimRun(testRun))
        assertFalse(service.claimRun(testRun))
        assertFalse(service.claimRun(testRun))
    }

    @Test
    void testAClaimFailureThatIsNotAConflictIsNotSwallowed() {
        SecureHttpClient client = [post: { String url, String body -> throw status(500) }] as SecureHttpClient

        assertThrows(InvalidRequestException, () ->
                new ReceiptService(client).claimRun(new TestRun(triggeredAt: TRIGGERED_AT.toString())))
    }

    @Test
    void testNoRunRecordReadsAsNull() {
        assertNull(serviceReturning(null).readRun())
    }

    private static SecureHttpClient objectStore(Map<String, String> objects) {
        return [
                get   : { String url -> objects[idOf(url)] },
                put   : { String url, String body ->
                    if (!objects.containsKey(idOf(url))) {
                        throw status(404)
                    }
                    objects[idOf(url)] = body
                },
                post  : { String url, String body -> objects[gson.fromJson(body, Map).id as String] = body },
                delete: { String url -> objects.remove(idOf(url)) }
        ] as SecureHttpClient
    }

    private static InvalidRequestException status(int code) {
        return new InvalidRequestException(new HttpResponseException(code, "test"), code)
    }

    private static String idOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1)
    }

    private static ReceiptService serviceReturning(String response) {
        new ReceiptService([get: { String url -> response }] as SecureHttpClient)
    }

    private static String receipt(Instant timestamp, String text) {
        gson.toJson([id     : "questionAsked", topic: "questionAsked", timestamp: timestamp.toString(),
                     payload: gson.toJson([questionId: "123", text: text])])
    }
}
