package com.trevorism.controller

import com.trevorism.model.TestResult
import com.trevorism.model.TestSuite
import com.trevorism.secure.Roles
import com.trevorism.secure.Secure
import com.trevorism.service.PromptEventTestService
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

    WebTestController(PromptEventTestService promptEventTestService) {
        this.promptEventTestService = promptEventTestService
    }

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
            promptEventTestService.triggerImmediateEvents(testSuite)
        } catch (Exception e) {
            log.warn("Could not trigger the prompt events", e)
        }
        return createTestResult(testSuite, false, 0, startTime)
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
