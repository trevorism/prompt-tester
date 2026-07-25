package com.trevorism.controller

import com.trevorism.model.TestResult
import com.trevorism.model.TestRun
import com.trevorism.model.TestSuite
import com.trevorism.service.PromptEventTestService
import com.trevorism.service.TestRunFinalizer
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull

class WebTestControllerTest {

    private final RecordingPromptEventTestService service = new RecordingPromptEventTestService()
    private final RecordingTestRunFinalizer finalizer = new RecordingTestRunFinalizer()

    @Test
    void testStartedRunAnswersWithoutAVerdictForTestingToPublish() {
        TestResult result = controller().testPromptSystem(new TestSuite(source: "prompt-tester", kind: "web"))

        assertEquals(1, service.triggerCount)
        assertNull(result.service)
        assertNull(result.kind)
        assertFalse(result.success)
        assertEquals(0, result.numberOfTests)
    }

    @Test
    void testStartingARunClosesOutThePreviousOne() {
        controller().testPromptSystem(new TestSuite(source: "prompt-tester", kind: "web"))

        assertEquals(1, finalizer.closeOutCount)
    }

    @Test
    void testUnrecognizedSuiteIsAVerdictAndTriggersNothing() {
        TestResult result = controller().testPromptSystem(new TestSuite(source: "some-other-app", kind: "web"))

        assertEquals(0, service.triggerCount)
        assertEquals(0, finalizer.closeOutCount)
        assertEquals("some-other-app", result.service)
        assertFalse(result.success)
        assertEquals(0, result.numberOfTests)
    }

    @Test
    void testTriggerFailureIsReportedImmediately() {
        service.explode = true

        TestResult result = controller().testPromptSystem(new TestSuite(source: "prompt-tester", kind: "web"))

        assertEquals(1, service.triggerCount)
        assertEquals("prompt-tester", result.service)
        assertEquals("web", result.kind)
        assertFalse(result.success)
        assertEquals(4, result.numberOfTests)
    }

    private WebTestController controller() {
        return new WebTestController(service, finalizer)
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

    private static class RecordingTestRunFinalizer extends TestRunFinalizer {

        int closeOutCount = 0

        RecordingTestRunFinalizer() {
            super(null, null, null)
        }

        @Override
        void closeOutStaleRun() {
            closeOutCount++
        }
    }
}
