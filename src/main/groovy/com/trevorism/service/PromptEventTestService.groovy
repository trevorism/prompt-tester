package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.event.ChannelClient
import com.trevorism.event.DefaultChannelClient
import com.trevorism.event.model.EventSubscription
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.TestRun
import com.trevorism.model.TestSuite
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Instant

/**
 * Validates prompt's four synchronous events end-to-end, split across two inbound requests because
 * this app is both the trigger and the subscriber:
 *   1. ensure a persistent, reused subscription exists per immediate topic -> this app's /webhook/{topic}
 *   2. record the run, then exercise prompt's real endpoints so each event fires
 *   3. best-effort delete the questions/answers created (subscriptions are left in place)
 *   4. later, as each event is pushed back to /webhook, TestRunFinalizer checks the receipts
 *
 * Nothing here waits on a receipt. A /test call that blocks starves the very webhook it is waiting
 * for, which pushes delivery into Pub/Sub retry backoff and fails the run.
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
     * Records the run and then fires the four immediate events. The record is written FIRST and is
     * complete as written: a receipt can arrive within a second of its event, and if the record is
     * not already there the webhook that completes the set has nothing to finalize against and no
     * later webhook comes to try again.
     *
     * Throwing is left to the caller's try/catch.
     */
    TestRun triggerImmediateEvents(TestSuite testSuite) {
        ensureSubscriptions()
        warmDependencies()

        TestRun testRun = new TestRun(source: testSuite.source, kind: testSuite.kind,
                suiteId: testSuite.id, triggeredAt: Instant.now().toString())
        receiptService.storeRun(testRun)

        List<String> questionIds = []
        List<String> answerIds = []
        try {
            // questionAsked + questionAnswered
            Map plain = createQuestion([text: "${MARKER} plain question".toString(), kind: "question"], questionIds)
            answerQuestion(plain.id as String, [text: "${MARKER} an answer".toString()], answerIds)
            // approvalRequested + approvalDecided
            Map approval = createQuestion([text: "${MARKER} approval request".toString(),
                                           kind: "approval", targetIdentityId: APPROVER], questionIds)
            answerQuestion(approval.id as String, [text: "${MARKER} looks good".toString(), approved: true], answerIds)

            log.info("Triggered immediate events for questions ${questionIds}")
            return testRun
        } finally {
            cleanup(questionIds, answerIds)
        }
    }

    /** One boolean per topic (in IMMEDIATE_TOPICS order): true if that event came back for this run. */
    List<Boolean> receiptStatus(TestRun testRun) {
        Instant since = Instant.parse(testRun.triggeredAt)
        return IMMEDIATE_TOPICS.collect { String topic ->
            receiptService.receivedSince(topic, since, MARKER)
        }
    }

    private void warmDependencies() {
        try {
            secureHttpClient.get("${PROMPT_URL}/api/ping".toString())
        } catch (Exception e) {
            log.warn("Could not warm prompt: ${e.message}")
        }
        receiptService.warm()
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
