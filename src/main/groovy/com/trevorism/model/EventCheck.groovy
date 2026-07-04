package com.trevorism.model

/**
 * The result for a single expected event topic within a /test run.
 */
class EventCheck {
    String topic
    boolean received
    int count
    String samplePayload
}
