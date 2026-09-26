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

@Singleton
class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class.name)
    private static final String OBJECT_URL = "https://memory.data.trevorism.com/object/prompt-event"
    private static final String CLAIM_URL = "https://memory.data.trevorism.com/object/prompt-run-claim"
    private static final String PING_URL = "https://memory.data.trevorism.com/ping"
    private static final String RUN_ID = "run"
    private static final int NOT_FOUND = 404
    private static final int CONFLICT = 409
    private static final int MAX_ATTEMPTS = 5

    long retryBackoffMillis = 50

    private final SecureHttpClient secureHttpClient
    private final Gson gson = new Gson()

    ReceiptService(@Named("promptTesterSecureHttpClient") SecureHttpClient secureHttpClient) {
        this.secureHttpClient = secureHttpClient
    }

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

    boolean claimRun(TestRun testRun) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                secureHttpClient.post(CLAIM_URL, gson.toJson([id: testRun.triggeredAt]))
                return true
            } catch (InvalidRequestException e) {
                if (e.statusCode != CONFLICT) {
                    throw e
                }
                if (claimExists(testRun.triggeredAt)) {
                    return false
                }
                backOff(attempt)
            }
        }
        log.warn("Could not claim run ${testRun.triggeredAt} in ${MAX_ATTEMPTS} attempts; leaving it unreported")
        return false
    }

    private boolean claimExists(String triggeredAt) {
        try {
            return secureHttpClient.get("${CLAIM_URL}/${triggeredAt}".toString()) as boolean
        } catch (InvalidRequestException e) {
            if (e.statusCode == NOT_FOUND) {
                return false
            }
            throw e
        }
    }

    void resetClaims() {
        try {
            secureHttpClient.put(CLAIM_URL, "[]")
        } catch (Exception e) {
            log.warn("Could not reset the run claims: ${e.message}")
        }
    }

    private void upsert(String id, String json) {
        String url = "${OBJECT_URL}/${id}".toString()
        InvalidRequestException lastConflict = null
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                secureHttpClient.put(url, json)
                return
            } catch (InvalidRequestException e) {
                if (e.statusCode == CONFLICT) {
                    lastConflict = e
                    backOff(attempt)
                    continue
                }
                if (e.statusCode != NOT_FOUND) {
                    throw e
                }
            }
            try {
                secureHttpClient.post(OBJECT_URL, json)
                return
            } catch (InvalidRequestException e) {
                if (e.statusCode != CONFLICT) {
                    throw e
                }
                lastConflict = e
                backOff(attempt)
            }
        }
        log.warn("Could not store ${id} in ${MAX_ATTEMPTS} attempts against a contended store")
        throw lastConflict
    }

    private void backOff(int attempt) {
        if (retryBackoffMillis > 0) {
            Thread.sleep(retryBackoffMillis * attempt)
        }
    }

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
