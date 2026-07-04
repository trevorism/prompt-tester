package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.https.SecureHttpClient
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Instant

/**
 * Records and reads per-topic event receipts in the shared memory service
 * (memory.data.trevorism.com), so a webhook push handled on one GAE instance is visible to a /test
 * call served by another. One object per topic (id = topic), upserted delete-then-post, mirroring
 * event-tester's DefaultEventTestService.storeEvent.
 */
@Singleton
class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class.name)
    private static final String OBJECT_URL = "https://memory.data.trevorism.com/object/prompt-event"

    private final SecureHttpClient secureHttpClient
    private final Gson gson = new Gson()

    ReceiptService(@Named("promptTesterSecureHttpClient") SecureHttpClient secureHttpClient) {
        this.secureHttpClient = secureHttpClient
    }

    /** Upsert the latest receipt for a topic (id = topic), stamped with the current time. */
    void store(String topic, String payload) {
        Map record = [id: topic, topic: topic, timestamp: Instant.now().toString(), payload: payload]
        try {
            secureHttpClient.delete("${OBJECT_URL}/${topic}".toString())
        } catch (Exception e) {
            log.debug("No prior receipt for ${topic} to delete: ${e.message}")
        }
        secureHttpClient.post(OBJECT_URL, gson.toJson(record))
    }

    /** True if a receipt exists for the topic with a timestamp at or after {@code since}. */
    boolean receivedSince(String topic, Instant since) {
        try {
            String response = secureHttpClient.get("${OBJECT_URL}/${topic}".toString())
            if (!response) return false
            String timestamp = gson.fromJson(response, Map)?.timestamp
            if (!timestamp) return false
            Instant received = Instant.parse(timestamp)
            return !received.isBefore(since)
        } catch (Exception e) {
            log.debug("No receipt yet for ${topic}: ${e.message}")
            return false
        }
    }
}
