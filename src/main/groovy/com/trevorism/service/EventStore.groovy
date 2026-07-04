package com.trevorism.service

import com.trevorism.model.ReceivedEvent
import jakarta.inject.Singleton

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory record of events pushed to the webhook endpoints, grouped by topic.
 *
 * This is a test harness, so a process-local store is intentional: it resets on redeploy and is
 * cleared at the start of every /test run. Nothing here depends on SecureHttpClient, so it is safe
 * to eagerly instantiate.
 */
@Singleton
class EventStore {

    private final Map<String, List<ReceivedEvent>> eventsByTopic = new ConcurrentHashMap<>()

    void record(String topic, String payload) {
        eventsByTopic
                .computeIfAbsent(topic, { String k -> Collections.synchronizedList(new ArrayList<ReceivedEvent>()) })
                .add(new ReceivedEvent(topic: topic, payload: payload))
    }

    List<ReceivedEvent> listAll() {
        eventsByTopic.values().collectMany { new ArrayList<>(it) }
    }

    List<ReceivedEvent> listByTopic(String topic) {
        new ArrayList<>(eventsByTopic.getOrDefault(topic, []))
    }

    int count(String topic) {
        eventsByTopic.getOrDefault(topic, []).size()
    }

    Map<String, Integer> counts() {
        eventsByTopic.collectEntries { String topic, List<ReceivedEvent> list -> [(topic): list.size()] }
    }

    void clear() {
        eventsByTopic.clear()
    }
}
