package com.trevorism.model

/**
 * Options for a /test run. All fields are optional; PromptEventTestService applies defaults.
 */
class EventTestRequest {
    /** Base URL the event service should push to (this app). Defaults to the deployed prompt-tester. */
    String selfUrl
    /** Base URL of the prompt app to drive. Defaults to https://prompt.action.trevorism.com. */
    String promptUrl
    /** How long to poll the webhook store for expected events before giving up. */
    Integer waitSeconds
    /** How far in the future to set due dates for the overdue/expired tests. */
    Integer dueDateOffsetSeconds
    /** Whether to include the scheduler-driven due-date events (overdue/expired). Default true. */
    Boolean includeDueDate
    /** If true, leave subscriptions + created questions in place for out-of-band inspection. */
    Boolean keep
}
