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
    private static final String PING_URL = "https://memory.data.trevorism.com/ping"

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

    /** True if a receipt exists for the topic, at or after {@code since}, from one of these questions. */
    boolean receivedSince(String topic, Instant since, Collection<String> questionIds) {
        try {
            String response = secureHttpClient.get("${OBJECT_URL}/${topic}".toString())
            if (!response) return false
            Map record = gson.fromJson(response, Map)
            String timestamp = record?.timestamp
            if (!timestamp) return false
            if (Instant.parse(timestamp).isBefore(since)) return false
            return belongsToRun(record?.payload as String, questionIds)
        } catch (Exception e) {
            log.debug("No receipt yet for ${topic}: ${e.message}")
            return false
        }
    }

    void warm() {
        try {
            secureHttpClient.get(PING_URL)
        } catch (Exception e) {
            log.warn("Could not warm the memory service: ${e.message}")
        }
    }

    private boolean belongsToRun(String payload, Collection<String> questionIds) {
        if (!payload || !questionIds) return false
        String questionId = gson.fromJson(payload, Map)?.questionId
        return questionId && questionIds.contains(questionId)
    }
}
