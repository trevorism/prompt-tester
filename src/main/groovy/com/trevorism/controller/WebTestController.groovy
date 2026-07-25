package com.trevorism.controller

import com.trevorism.model.TestResult
import com.trevorism.model.TestSuite
import com.trevorism.secure.Roles
import com.trevorism.secure.Secure
import com.trevorism.service.PromptEventTestService
import com.trevorism.service.TestRunFinalizer
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Controller("/test")
@ExecuteOn(TaskExecutors.BLOCKING)
class WebTestController {

    private static final Logger log = LoggerFactory.getLogger(WebTestController.class.name)

    private final PromptEventTestService promptEventTestService
    private final TestRunFinalizer testRunFinalizer

    WebTestController(PromptEventTestService promptEventTestService, TestRunFinalizer testRunFinalizer) {
        this.promptEventTestService = promptEventTestService
        this.testRunFinalizer = testRunFinalizer
    }

    /**
     * A run that started successfully answers WITHOUT a service or kind. That absence is what tells
     * the testing service there is no verdict here to publish, leaving this app to emit the
     * testResult itself once the events return, the way a unit or cucumber run does. A request that
     * never got as far as firing an event is a verdict, and is answered as one.
     */
    @Tag(name = "Test Endpoint Operations")
    @Operation(summary = "Triggers the prompt event check; the verdict is published as a testResult event once the events return **Secure")
    @Post(produces = MediaType.APPLICATION_JSON, consumes = MediaType.APPLICATION_JSON)
    @Secure(Roles.USER)
    TestResult testPromptSystem(@Body TestSuite testSuite) {
        long startTime = System.currentTimeMillis()
        if (testSuite.source != "prompt-tester" || testSuite.kind != "web") {
            log.info("Attempting to test an invalid test suite")
            return createTestResult(testSuite, false, 0, startTime)
        }
        try {
            testRunFinalizer.closeOutStaleRun()
            promptEventTestService.triggerImmediateEvents(testSuite)
        } catch (Exception e) {
            log.warn("Could not trigger the prompt events", e)
            return createTestResult(testSuite, false, PromptEventTestService.IMMEDIATE_TOPICS.size(), startTime)
        }
        return acceptedResult(startTime)
    }

    private static TestResult acceptedResult(long startTime) {
        new TestResult([
                success       : false,
                numberOfTests : 0,
                durationMillis: (int) (System.currentTimeMillis() - startTime)
        ])
    }

    private static TestResult createTestResult(TestSuite testSuite, boolean success, int numberOfTests, long startTime) {
        int duration = (int) (System.currentTimeMillis() - startTime)
        new TestResult([
                service       : testSuite.source,
                kind          : testSuite.kind,
                success       : success,
                numberOfTests : numberOfTests,
                durationMillis: duration
        ])
    }
}
