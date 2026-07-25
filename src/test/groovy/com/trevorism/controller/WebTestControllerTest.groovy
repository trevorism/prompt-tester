package com.trevorism.controller

import com.trevorism.model.TestResult
import com.trevorism.model.TestRun
import com.trevorism.model.TestSuite
import com.trevorism.service.PromptEventTestService
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse

class WebTestControllerTest {

    @Test
    void testValidSuiteTriggersOnceAndReportsNothingVerifiedYet() {
        RecordingPromptEventTestService service = new RecordingPromptEventTestService()

        TestResult result = new WebTestController(service)
                .testPromptSystem(new TestSuite(source: "prompt-tester", kind: "web"))

        assertEquals(1, service.triggerCount)
        assertFalse(result.success)
        assertEquals(0, result.numberOfTests)
        assertEquals("prompt-tester", result.service)
        assertEquals("web", result.kind)
    }

    @Test
    void testUnrecognizedSuiteTriggersNothing() {
        RecordingPromptEventTestService service = new RecordingPromptEventTestService()

        TestResult result = new WebTestController(service)
                .testPromptSystem(new TestSuite(source: "some-other-app", kind: "web"))

        assertEquals(0, service.triggerCount)
        assertFalse(result.success)
        assertEquals(0, result.numberOfTests)
    }

    @Test
    void testTriggerFailureStillReturnsAResult() {
        RecordingPromptEventTestService service = new RecordingPromptEventTestService(explode: true)

        TestResult result = new WebTestController(service)
                .testPromptSystem(new TestSuite(source: "prompt-tester", kind: "web"))

        assertEquals(1, service.triggerCount)
        assertFalse(result.success)
        assertEquals(0, result.numberOfTests)
    }

    private static class RecordingPromptEventTestService extends PromptEventTestService {

        int triggerCount = 0
        boolean explode = false

        RecordingPromptEventTestService() {
            super(null, null)
        }

        @Override
        TestRun triggerImmediateEvents(TestSuite testSuite) {
            triggerCount++
            if (explode) {
                throw new RuntimeException("Could not reach prompt")
            }
            return new TestRun(source: testSuite.source, kind: testSuite.kind)
        }
    }
}
