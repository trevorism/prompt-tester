package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.http.util.InvalidRequestException
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.TestRun
import org.apache.hc.client5.http.HttpResponseException
import org.junit.jupiter.api.Test

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The 18:07 failure: four pushes landed within 1.2s, three were still checking receipts when the set
 * completed, and all three went on to report the run. Two testResults and a finalizer that fell over.
 *
 * Reproducing that by triggering runs is a dice roll -- it needs two handlers mid-check at the moment
 * the last receipt lands -- so the webhooks are driven straight at the finalizer from a barrier here.
 *
 * FakeMemory is the memory service's actual concurrency model, not a stub: a kind is one blob with a
 * generation, reads take a snapshot, and a write whose generation moved underneath it is a 409. That
 * model was checked against the deployed service -- ten concurrent creates of one id answered 1 x 201
 * and 9 x 409, leaving one row.
 */
class TestRunFinalizerConcurrencyTest {

    private static final Gson gson = new Gson()
    private static final int WEBHOOKS = 4
    private static final int ITERATIONS = 100

    @Test
    void testOnlyOneOfManySimultaneousWebhooksReportsTheRun() {
        int iterationsThatRaced = 0

        ITERATIONS.times {
            FakeMemory memory = new FakeMemory()
            Instant triggeredAt = Instant.now().minusSeconds(5)
            memory.seedCompleteRun(triggeredAt)

            TestRunFinalizer finalizer = finalizerFor(memory)
            List<Throwable> escaped = runTogether(WEBHOOKS) { finalizer.finalizeIfComplete() }

            assertTrue(escaped.isEmpty(), "a webhook threw: ${escaped}")
            assertEquals(1, memory.publishedEvents.size(),
                    "run ${triggeredAt} was reported ${memory.publishedEvents.size()} times")
            if (memory.claimAttempts.get() > 1) {
                iterationsThatRaced++
            }
        }

        assertTrue(iterationsThatRaced > 0,
                "no iteration got two webhooks as far as the claim, so the race was never entered")
    }

    @Test
    void testTheLosersOfTheRaceStillAnswerTheirWebhook() {
        FakeMemory memory = new FakeMemory()
        memory.seedCompleteRun(Instant.now().minusSeconds(5))
        TestRunFinalizer finalizer = finalizerFor(memory)

        List<Throwable> escaped = runTogether(WEBHOOKS) { finalizer.finalizeIfComplete() }

        assertTrue(escaped.isEmpty(), "a webhook threw: ${escaped}")
        assertEquals(1, memory.publishedEvents.size())
    }

    @Test
    void testAContendedReceiptStoreStillLandsEveryReceipt() {
        FakeMemory memory = new FakeMemory()
        ReceiptService receiptService = receiptServiceFor(memory)

        List<Throwable> escaped = runTogether(PromptEventTestService.IMMEDIATE_TOPICS.size()) { int index ->
            String topic = PromptEventTestService.IMMEDIATE_TOPICS[index]
            receiptService.store(topic, "${PromptEventTestService.MARKER} ${topic}".toString())
        }

        assertTrue(escaped.isEmpty(), "a receipt store threw: ${escaped}")
        PromptEventTestService.IMMEDIATE_TOPICS.each { String topic ->
            assertTrue(memory.read("prompt-event", topic) != null, "${topic} receipt was lost")
        }
    }

    private static TestRunFinalizer finalizerFor(FakeMemory memory) {
        ReceiptService receiptService = receiptServiceFor(memory)
        SecureHttpClient client = memory.asClient()
        return new TestRunFinalizer(new PromptEventTestService(client, receiptService), receiptService, client)
    }

    private static ReceiptService receiptServiceFor(FakeMemory memory) {
        ReceiptService receiptService = new ReceiptService(memory.asClient())
        receiptService.retryBackoffMillis = 0
        return receiptService
    }

    private static List<Throwable> runTogether(int count, Closure work) {
        CyclicBarrier barrier = new CyclicBarrier(count)
        List<Throwable> escaped = new CopyOnWriteArrayList<>()
        List<Thread> threads = (0..<count).collect { int index ->
            Thread.start {
                try {
                    barrier.await()
                    work.maximumNumberOfParameters == 0 ? work.call() : work.call(index)
                } catch (Throwable t) {
                    escaped.add(t)
                }
            }
        }
        threads*.join()
        return escaped
    }

    private static class FakeMemory {

        final List<String> publishedEvents = new CopyOnWriteArrayList<>()
        final AtomicInteger claimAttempts = new AtomicInteger()
        final AtomicInteger conflictsServed = new AtomicInteger()

        private final Map<String, List<Map<String, Object>>> blobs = [:]
        private final Map<String, Integer> generations = [:]
        private final Object lock = new Object()

        void seedCompleteRun(Instant triggeredAt) {
            put("prompt-event", [[id: "run", source: "prompt-tester", kind: "web",
                                  triggeredAt: triggeredAt.toString()] as Map<String, Object>] +
                    PromptEventTestService.IMMEDIATE_TOPICS.collect { String topic ->
                        [id       : topic, topic: topic, timestamp: triggeredAt.plusSeconds(1).toString(),
                         payload  : "${PromptEventTestService.MARKER} ${topic}".toString()] as Map<String, Object>
                    })
        }

        Map<String, Object> read(String kind, String id) {
            synchronized (lock) {
                return blobs.getOrDefault(kind, []).find { it.id == id }
            }
        }

        SecureHttpClient asClient() {
            return [
                    get   : { String url -> handleGet(url) },
                    post  : { String url, String body -> handlePost(url, body) },
                    put   : { String url, String body -> handlePut(url, body) },
                    delete: { String url -> handleDelete(url) }
            ] as SecureHttpClient
        }

        private String handleGet(String url) {
            String[] target = targetOf(url)
            Map<String, Object> found = read(target[0], target[1])
            if (found == null) {
                throw status(404)
            }
            return gson.toJson(found)
        }

        private String handlePost(String url, String body) {
            if (url.contains("/event/")) {
                publishedEvents.add(body)
                return "{}"
            }
            String kind = targetOf(url)[0]
            if (kind == "prompt-run-claim") {
                claimAttempts.incrementAndGet()
            }
            Map<String, Object> data = gson.fromJson(body, Map)
            Snapshot snapshot = snapshot(kind)
            if (snapshot.items.any { it.id == data.id }) {
                throw conflict()
            }
            Thread.yield()
            commit(kind, snapshot.generation, snapshot.items + [data])
            return body
        }

        private String handlePut(String url, String body) {
            String[] target = targetOf(url)
            if (target[1] == null) {
                Snapshot snapshot = snapshot(target[0])
                Thread.yield()
                commit(target[0], snapshot.generation, gson.fromJson(body, List) as List<Map<String, Object>>)
                return body
            }
            Snapshot snapshot = snapshot(target[0])
            Map<String, Object> existing = snapshot.items.find { it.id == target[1] }
            if (existing == null) {
                throw status(404)
            }
            Thread.yield()
            List<Map<String, Object>> updated = snapshot.items.findAll { it.id != target[1] } +
                    [gson.fromJson(body, Map) as Map<String, Object>]
            commit(target[0], snapshot.generation, updated)
            return body
        }

        private String handleDelete(String url) {
            String[] target = targetOf(url)
            Snapshot snapshot = snapshot(target[0])
            Thread.yield()
            commit(target[0], snapshot.generation, snapshot.items.findAll { it.id != target[1] })
            return ""
        }

        private void put(String kind, List<Map<String, Object>> items) {
            synchronized (lock) {
                blobs[kind] = items
                generations[kind] = generations.getOrDefault(kind, 0) + 1
            }
        }

        private Snapshot snapshot(String kind) {
            synchronized (lock) {
                return new Snapshot(generation: generations.getOrDefault(kind, 0),
                        items: blobs.getOrDefault(kind, []).collect { new LinkedHashMap<String, Object>(it) })
            }
        }

        private void commit(String kind, int expectedGeneration, List<Map<String, Object>> items) {
            synchronized (lock) {
                if (generations.getOrDefault(kind, 0) != expectedGeneration) {
                    throw conflict()
                }
                blobs[kind] = items
                generations[kind] = expectedGeneration + 1
            }
        }

        private InvalidRequestException conflict() {
            conflictsServed.incrementAndGet()
            return status(409)
        }

        private static String[] targetOf(String url) {
            String path = url.substring(url.indexOf("/object/") + "/object/".length())
            String[] parts = path.split("/")
            return [parts[0], parts.length > 1 ? parts[1] : null] as String[]
        }

        private static InvalidRequestException status(int code) {
            return new InvalidRequestException(new HttpResponseException(code, "fake"), code)
        }
    }

    private static class Snapshot {
        int generation
        List<Map<String, Object>> items
    }
}
