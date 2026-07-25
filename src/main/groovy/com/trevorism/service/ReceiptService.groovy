package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.http.util.InvalidRequestException
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.TestRun
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Instant

/**
 * Records and reads per-topic event receipts in the shared memory service
 * (memory.data.trevorism.com), so a webhook push handled on one GAE instance is visible to a /test
 * call served by another. One object per topic (id = topic), updated in place.
 *
 * Also hands out the run's publish claim. Four pushes can land within a second of each other and
 * each finds the same complete set of receipts, so "have all the events arrived" cannot decide who
 * reports the run. Creating the claim can: the memory service admits one create per id and answers
 * 409 to the rest, whether the loser saw the row or lost the write race behind it.
 */
@Singleton
class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class.name)
    private static final String OBJECT_URL = "https://memory.data.trevorism.com/object/prompt-event"
    private static final String CLAIM_URL = "https://memory.data.trevorism.com/object/prompt-run-claim"
    private static final String PING_URL = "https://memory.data.trevorism.com/ping"
    private static final String RUN_ID = "run"
    private static final int NOT_FOUND = 404
    private static final int CONFLICT = 409

    private final SecureHttpClient secureHttpClient
    private final Gson gson = new Gson()

    ReceiptService(@Named("promptTesterSecureHttpClient") SecureHttpClient secureHttpClient) {
        this.secureHttpClient = secureHttpClient
    }

    /** Upsert the latest receipt for a topic (id = topic), stamped with the current time. */
    void store(String topic, String payload) {
        Map record = [id: topic, topic: topic, timestamp: Instant.now().toString(), payload: payload]
        upsert(topic, gson.toJson(record))
    }

    void storeRun(TestRun testRun) {
        upsert(RUN_ID, gson.toJson(testRun))
    }

    TestRun readRun() {
        try {
            String response = secureHttpClient.get("${OBJECT_URL}/${RUN_ID}".toString())
            if (!response) return null
            return gson.fromJson(response, TestRun)
        } catch (Exception e) {
            log.debug("No test run recorded yet: ${e.message}")
            return null
        }
    }

    /** True if this caller is the one that gets to report the run, false if someone else already has. */
    boolean claimRun(TestRun testRun) {
        try {
            secureHttpClient.post(CLAIM_URL, gson.toJson([id: testRun.triggeredAt]))
            return true
        } catch (InvalidRequestException e) {
            if (e.statusCode == CONFLICT) {
                return false
            }
            throw e
        }
    }

    /** Claims are keyed by trigger time, so clearing them at the start of a run just bounds the file. */
    void resetClaims() {
        try {
            secureHttpClient.put(CLAIM_URL, "[]")
        } catch (Exception e) {
            log.warn("Could not reset the run claims: ${e.message}")
        }
    }

    private void upsert(String id, String json) {
        try {
            secureHttpClient.put("${OBJECT_URL}/${id}".toString(), json)
            return
        } catch (InvalidRequestException e) {
            if (e.statusCode != NOT_FOUND) {
                throw e
            }
        }
        try {
            secureHttpClient.post(OBJECT_URL, json)
        } catch (InvalidRequestException e) {
            if (e.statusCode != CONFLICT) {
                throw e
            }
            secureHttpClient.put("${OBJECT_URL}/${id}".toString(), json)
        }
    }

    /** True if a receipt exists for the topic, at or after {@code since}, carrying this test's marker. */
    boolean receivedSince(String topic, Instant since, String marker) {
        try {
            String response = secureHttpClient.get("${OBJECT_URL}/${topic}".toString())
            if (!response) return false
            Map record = gson.fromJson(response, Map)
            String timestamp = record?.timestamp
            if (!timestamp) return false
            if (Instant.parse(timestamp).isBefore(since)) return false
            return (record?.payload as String)?.contains(marker)
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

}
