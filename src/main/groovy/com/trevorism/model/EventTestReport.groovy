package com.trevorism.model

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The outcome of a /test run: whether every expected event was received, plus per-topic detail and
 * a trace of what the harness did.
 */
class EventTestReport {
    boolean success
    String selfUrl
    String promptUrl
    List<EventCheck> checks = []
    List<String> subscriptions = []
    List<String> questionIds = []
    List<String> messages = []
    int durationMillis
    String date = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
}
