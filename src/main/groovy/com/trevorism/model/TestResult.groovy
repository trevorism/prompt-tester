package com.trevorism.model

import java.time.Instant
import java.time.temporal.ChronoUnit

class TestResult {
    String service
    String kind
    boolean success
    int numberOfTests
    int durationMillis
    String date = Instant.now().truncatedTo( ChronoUnit.SECONDS).toString()
}
