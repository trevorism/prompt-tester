package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.event.ChannelClient
import com.trevorism.event.model.EventSubscription
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.EventCheck
import com.trevorism.model.EventTestReport
import com.trevorism.model.EventTestRequest
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Drives the prompt app end-to-end to validate its event publishing:
 *   1. ensure the six topics exist and subscribe each to this app's /webhook/{topic}
 *   2. exercise prompt's real endpoints so every event fires
 *   3. poll the in-memory EventStore for the pushed payloads
 *   4. (unless keep=true) delete the subscriptions and test data
 *
 * Lazy @Singleton: SecureHttpClient is only used inside runTests(), which always executes within the
 * /test request scope -- honoring the "no eager bean may depend on SecureHttpClient" constraint.
 */
@Singleton
class PromptEventTestService {

    private static final Logger log = LoggerFactory.getLogger(PromptEventTestService.class.name)

    static final String DEFAULT_SELF_URL = "https://prompt-tester.testing.trevorism.com"
    static final String DEFAULT_PROMPT_URL = "https://prompt.action.trevorism.com"
    static final String MARKER = "[event-test]"
    static final String APPROVER = "prompt-tester"

    // Immediate events fire synchronously when prompt's endpoints are called.
    static final List<String> IMMEDIATE_TOPICS = ["questionAsked", "questionAnswered", "approvalRequested", "approvalDecided"]
    // Due-date events fire later, when the schedule service calls prompt's due-date callback.
    static final List<String> DUE_DATE_TOPICS = ["questionOverdue", "approvalExpired"]
    static final List<String> ALL_TOPICS = IMMEDIATE_TOPICS + DUE_DATE_TOPICS

    private final ChannelClient channelClient
    private final SecureHttpClient secureHttpClient
    private final EventStore eventStore
    private final Gson gson = new Gson()

    PromptEventTestService(ChannelClient channelClient, SecureHttpClient secureHttpClient,
                           EventStore eventStore) {
        this.channelClient = channelClient
        this.secureHttpClient = secureHttpClient
        this.eventStore = eventStore
    }

    EventTestReport runTests(EventTestRequest request) {
        long start = System.currentTimeMillis()
        EventTestRequest req = request ?: new EventTestRequest()

        String selfUrl = trimTrailingSlash(req.selfUrl ?: DEFAULT_SELF_URL)
        String promptUrl = trimTrailingSlash(req.promptUrl ?: DEFAULT_PROMPT_URL)
        boolean includeDueDate = req.includeDueDate == null ? true : req.includeDueDate
        int dueOffset = req.dueDateOffsetSeconds ?: 45
        int waitSeconds = req.waitSeconds ?: (includeDueDate ? 180 : 60)
        boolean keep = req.keep ?: false

        EventTestReport report = new EventTestReport(selfUrl: selfUrl, promptUrl: promptUrl)
        List<String> subs = []
        List<String> questionIds = []
        List<String> answerIds = []

        try {
            eventStore.clear()
            report.messages << "Cleared event store"

            ensureTopics(report)
            createSubscriptions(selfUrl, start, subs, report)
            // Give the event service a moment to activate the new subscriptions before publishing.
            Thread.sleep(2000)

            // 1) questionAsked, 2) questionAnswered
            Map plain = createQuestion(promptUrl, [text: "${MARKER} plain question ${start}".toString(), kind: "question"], questionIds)
            answerQuestion(promptUrl, plain.id as String, [text: "${MARKER} an answer".toString()], answerIds)
            // 3) approvalRequested, 4) approvalDecided
            Map approval = createQuestion(promptUrl, [text: "${MARKER} approval request ${start}".toString(),
                                                      kind: "approval", targetIdentityId: APPROVER], questionIds)
            answerQuestion(promptUrl, approval.id as String, [text: "${MARKER} looks good".toString(), approved: true], answerIds)
            report.messages << "Triggered immediate events (asked, answered, approvalRequested, approvalDecided)"

            List<String> expected = new ArrayList<>(IMMEDIATE_TOPICS)
            if (includeDueDate) {
                // 5) questionOverdue, 6) approvalExpired -- leave unanswered so the scheduler fires them.
                // Compute the due date from NOW (not the run start): creating the subscriptions and the
                // immediate-event questions takes tens of seconds, so a start-relative offset can already
                // be in the past by the time these are created, and prompt then skips scheduling the callback.
                long dueAt = System.currentTimeMillis() + (dueOffset * 1000L)
                createQuestion(promptUrl, [text: "${MARKER} overdue question ${start}".toString(),
                                           kind: "question", dueDate: dueAt], questionIds)
                createQuestion(promptUrl, [text: "${MARKER} expiring approval ${start}".toString(),
                                           kind: "approval", targetIdentityId: APPROVER, dueDate: dueAt], questionIds)
                expected.addAll(DUE_DATE_TOPICS)
                report.messages << "Scheduled due-date questions (due in ${dueOffset}s); overdue/expired fire via the schedule service".toString()
            }

            boolean allArrived = pollForEvents(expected, waitSeconds)
            report.messages << (allArrived
                    ? "All ${expected.size()} expected events received".toString()
                    : "Timed out after ${waitSeconds}s waiting for events".toString())

            report.checks = expected.collect { String topic ->
                int c = eventStore.count(topic)
                new EventCheck(topic: topic, received: c > 0, count: c, samplePayload: firstPayload(topic))
            }
            report.success = report.checks.every { it.received }
        } catch (Exception e) {
            log.warn("Event test run failed", e)
            report.messages << "ERROR: ${e.class.simpleName}: ${e.message}".toString()
            report.success = false
        } finally {
            report.subscriptions = subs
            report.questionIds = questionIds
            if (keep) {
                report.messages << "keep=true: left ${subs.size()} subscription(s) and ${questionIds.size()} question(s) in place for inspection".toString()
            } else {
                cleanup(promptUrl, subs, questionIds, answerIds, report)
            }
            report.durationMillis = (int) (System.currentTimeMillis() - start)
        }
        return report
    }

    private void ensureTopics(EventTestReport report) {
        List<String> existing = []
        try {
            existing = channelClient.listTopics() ?: []
        } catch (Exception e) {
            report.messages << "WARN could not list topics (${e.message}); attempting creates".toString()
        }
        ALL_TOPICS.each { String topic ->
            if (!existing.contains(topic)) {
                try {
                    channelClient.createTopic(topic)
                    report.messages << "Created topic ${topic}".toString()
                } catch (Exception e) {
                    report.messages << "WARN create topic ${topic}: ${e.message}".toString()
                }
            }
        }
    }

    private void createSubscriptions(String selfUrl, long runId, List<String> created, EventTestReport report) {
        ALL_TOPICS.each { String topic ->
            EventSubscription sub = new EventSubscription()
            sub.name = "${topic}-tester-${runId}".toString()
            sub.topic = topic
            sub.url = "${selfUrl}/webhook/${topic}".toString()
            sub.ackDeadlineSeconds = 60
            try {
                channelClient.createSubscription(sub)
                created << sub.name
            } catch (Exception e) {
                report.messages << "WARN create subscription for ${topic}: ${e.message}".toString()
            }
        }
        report.messages << "Created ${created.size()}/${ALL_TOPICS.size()} subscriptions -> ${selfUrl}/webhook/{topic}".toString()
    }

    private Map createQuestion(String promptUrl, Map body, List<String> track) {
        String resp = secureHttpClient.post("${promptUrl}/api/question/".toString(), gson.toJson(body))
        Map created = gson.fromJson(resp, Map)
        if (created?.id) track << (created.id as String)
        return created
    }

    private Map answerQuestion(String promptUrl, String questionId, Map body, List<String> track) {
        String resp = secureHttpClient.post("${promptUrl}/api/question/${questionId}/answer".toString(), gson.toJson(body))
        Map created = gson.fromJson(resp, Map)
        if (created?.id) track << (created.id as String)
        return created
    }

    private boolean pollForEvents(List<String> expected, int waitSeconds) {
        long deadline = System.currentTimeMillis() + (waitSeconds * 1000L)
        while (System.currentTimeMillis() < deadline) {
            if (expected.every { eventStore.count(it) > 0 }) {
                return true
            }
            Thread.sleep(3000)
        }
        return expected.every { eventStore.count(it) > 0 }
    }

    private String firstPayload(String topic) {
        List received = eventStore.listByTopic(topic)
        received ? received[0].payload : null
    }

    private void cleanup(String promptUrl, List<String> subs, List<String> questionIds, List<String> answerIds, EventTestReport report) {
        answerIds.each { String id ->
            try { secureHttpClient.delete("${promptUrl}/api/answer/${id}".toString()) }
            catch (Exception e) { report.messages << "WARN delete answer ${id}: ${e.message}".toString() }
        }
        questionIds.each { String id ->
            try { secureHttpClient.delete("${promptUrl}/api/question/${id}".toString()) }
            catch (Exception e) { report.messages << "WARN delete question ${id}: ${e.message}".toString() }
        }
        subs.each { String name ->
            try { channelClient.deleteSubscription(name) }
            catch (Exception e) { report.messages << "WARN delete subscription ${name}: ${e.message}".toString() }
        }
        report.messages << "Cleaned up ${subs.size()} subscription(s), ${questionIds.size()} question(s), ${answerIds.size()} answer(s)".toString()
    }

    private static String trimTrailingSlash(String s) {
        s?.endsWith("/") ? s[0..-2] : s
    }
}
