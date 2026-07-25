package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.https.SecureHttpClient
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class ReceiptServiceTest {

    private static final Gson gson = new Gson()
    private static final Instant TRIGGERED_AT = Instant.parse("2026-07-25T14:00:00Z")

    @Test
    void testReceiptFromThisRunIsAccepted() {
        ReceiptService service = serviceReturning(receipt(TRIGGERED_AT.plusSeconds(5), "123"))
        assertTrue(service.receivedSince("questionAsked", TRIGGERED_AT, ["123"]))
    }

    @Test
    void testRedeliveryFromAnotherRunIsRejected() {
        ReceiptService service = serviceReturning(receipt(TRIGGERED_AT.plusSeconds(1), "999"))
        assertFalse(service.receivedSince("approvalRequested", TRIGGERED_AT, ["123"]))
    }

    @Test
    void testReceiptOlderThanTheTriggerIsRejected() {
        ReceiptService service = serviceReturning(receipt(TRIGGERED_AT.minusSeconds(5), "123"))
        assertFalse(service.receivedSince("questionAsked", TRIGGERED_AT, ["123"]))
    }

    @Test
    void testReceiptWithoutAQuestionIdIsRejected() {
        String payload = gson.toJson([id: "questionAsked", topic: "questionAsked",
                                      timestamp: TRIGGERED_AT.plusSeconds(5).toString(), payload: "{}"])
        assertFalse(serviceReturning(payload).receivedSince("questionAsked", TRIGGERED_AT, ["123"]))
    }

    @Test
    void testNoReceiptIsRejected() {
        assertFalse(serviceReturning(null).receivedSince("questionAsked", TRIGGERED_AT, ["123"]))
    }

    @Test
    void testRunWithNoCreatedQuestionsIsRejected() {
        ReceiptService service = serviceReturning(receipt(TRIGGERED_AT.plusSeconds(5), "123"))
        assertFalse(service.receivedSince("questionAsked", TRIGGERED_AT, []))
    }

    @Test
    void testTransportFailureIsRejected() {
        SecureHttpClient client = [get: { String url -> throw new RuntimeException("not found") }] as SecureHttpClient
        assertFalse(new ReceiptService(client).receivedSince("questionAsked", TRIGGERED_AT, ["123"]))
    }

    private static ReceiptService serviceReturning(String response) {
        new ReceiptService([get: { String url -> response }] as SecureHttpClient)
    }

    private static String receipt(Instant timestamp, String questionId) {
        gson.toJson([id       : "questionAsked", topic: "questionAsked", timestamp: timestamp.toString(),
                     payload  : gson.toJson([questionId: questionId])])
    }
}
