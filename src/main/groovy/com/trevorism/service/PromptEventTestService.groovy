package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.event.ChannelClient
import com.trevorism.event.DefaultChannelClient
import com.trevorism.event.model.EventSubscription
import com.trevorism.https.SecureHttpClient
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Instant

/**
 * Validates prompt's four synchronous events end-to-end:
 *   1. ensure a persistent, reused subscription exists per immediate topic -> this app's /webhook/{topic}
 *   2. exercise prompt's real endpoints so each event fires
 *   3. verify each event was pushed to our webhook and recorded (via ReceiptService / memory service)
 *   4. best-effort delete the questions/answers created (subscriptions are left in place)
 *
 * All outbound calls use the app-credentials client (promptTesterSecureHttpClient), like event-tester,
 * so behavior does not depend on the caller's token. The scheduler-driven due-date events
 * (questionOverdue / approvalExpired) are intentionally out of scope for the standard /test run.
 */
@Singleton
class PromptEventTestService {

    private static final Logger log = LoggerFactory.getLogger(PromptEventTestService.class.name)

    static final String SELF_URL = "https://prompt-tester.testing.trevorism.com"
    static final String PROMPT_URL = "https://prompt.action.trevorism.com"
    static final String MARKER = "[prompt-tester]"
    static final String APPROVER = "prompt-tester"

    /** The four events prompt emits synchronously when its endpoints are called. */
    static final List<String> IMMEDIATE_TOPICS = ["questionAsked", "questionAnswered", "approvalRequested", "approvalDecided"]

    private static final int POLL_TIMEOUT_SECONDS = 60
    private static final int POLL_INTERVAL_MILLIS = 3000

    private final SecureHttpClient secureHttpClient
    private final ChannelClient channelClient
    private final ReceiptService receiptService
    private final Gson gson = new Gson()

    PromptEventTestService(@Named("promptTesterSecureHttpClient") SecureHttpClient secureHttpClient,
                           ReceiptService receiptService) {
        this.secureHttpClient = secureHttpClient
        this.channelClient = new DefaultChannelClient(secureHttpClient)
        this.receiptService = receiptService
    }

    /**
     * Runs the immediate-event validation and returns one boolean per topic (in IMMEDIATE_TOPICS
     * order): true if that event was received by our webhook after the trigger. Throwing is left to
     * the caller's try/catch (an exception ⇒ overall failure).
     */
    List<Boolean> runImmediateChecks() {
        ensureSubscriptions()

        List<String> questionIds = []
        List<String> answerIds = []
        Instant triggeredAt = Instant.now()
        try {
            // questionAsked + questionAnswered
            Map plain = createQuestion([text: "${MARKER} plain question".toString(), kind: "question"], questionIds)
            answerQuestion(plain.id as String, [text: "${MARKER} an answer".toString()], answerIds)
            // approvalRequested + approvalDecided
            Map approval = createQuestion([text: "${MARKER} approval request".toString(),
                                           kind: "approval", targetIdentityId: APPROVER], questionIds)
            answerQuestion(approval.id as String, [text: "${MARKER} looks good".toString(), approved: true], answerIds)

            return pollForReceipts(triggeredAt)
        } finally {
            cleanup(questionIds, answerIds)
        }
    }

    /** Ensure a persistent subscription (stable name, reused across runs) exists per immediate topic. */
    private void ensureSubscriptions() {
        List<String> topics = safeList { channelClient.listTopics() }
        List<String> subNames = safeList { channelClient.listSubscriptions()*.name }
        IMMEDIATE_TOPICS.each { String topic ->
            if (!topics.contains(topic)) {
                try { channelClient.createTopic(topic) }
                catch (Exception e) { log.warn("Could not ensure topic ${topic}: ${e.message}") }
            }
            String name = "${topic}-prompt-tester".toString()
            if (!subNames.contains(name)) {
                EventSubscription sub = new EventSubscription()
                sub.name = name
                sub.topic = topic
                sub.url = "${SELF_URL}/webhook/${topic}".toString()
                sub.ackDeadlineSeconds = 60
                try { channelClient.createSubscription(sub); log.info("Created subscription ${name}") }
                catch (Exception e) { log.warn("Could not create subscription ${name}: ${e.message}") }
            }
        }
    }

    private List<Boolean> pollForReceipts(Instant since) {
        long deadline = System.currentTimeMillis() + (POLL_TIMEOUT_SECONDS * 1000L)
        List<Boolean> results = IMMEDIATE_TOPICS.collect { false }
        while (System.currentTimeMillis() < deadline) {
            IMMEDIATE_TOPICS.eachWithIndex { String topic, int i ->
                if (!results[i]) results[i] = receiptService.receivedSince(topic, since)
            }
            if (results.every { it }) break
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        log.info("Immediate event receipts: ${[IMMEDIATE_TOPICS, results].transpose()}")
        return results
    }

    private Map createQuestion(Map body, List<String> track) {
        String resp = secureHttpClient.post("${PROMPT_URL}/api/question/".toString(), gson.toJson(body))
        Map created = gson.fromJson(resp, Map)
        if (created?.id) track << (created.id as String)
        return created
    }

    private Map answerQuestion(String questionId, Map body, List<String> track) {
        String resp = secureHttpClient.post("${PROMPT_URL}/api/question/${questionId}/answer".toString(), gson.toJson(body))
        Map created = gson.fromJson(resp, Map)
        if (created?.id) track << (created.id as String)
        return created
    }

    private void cleanup(List<String> questionIds, List<String> answerIds) {
        answerIds.each { String id ->
            try { secureHttpClient.delete("${PROMPT_URL}/api/answer/${id}".toString()) }
            catch (Exception e) { log.warn("Could not delete answer ${id}: ${e.message}") }
        }
        questionIds.each { String id ->
            try { secureHttpClient.delete("${PROMPT_URL}/api/question/${id}".toString()) }
            catch (Exception e) { log.warn("Could not delete question ${id}: ${e.message}") }
        }
    }

    private static List<String> safeList(Closure<List<String>> supplier) {
        try { return supplier.call() ?: [] } catch (Exception ignored) { return [] }
    }
}
