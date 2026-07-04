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
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Controller("/test")
class WebTestController {

    private static final Logger log = LoggerFactory.getLogger(WebTestController.class.name)

    private final PromptEventTestService promptEventTestService

    WebTestController(PromptEventTestService promptEventTestService) {
        this.promptEventTestService = promptEventTestService
    }

    @Tag(name = "Test Endpoint Operations")
    @Operation(summary = "Validates the prompt event system end-to-end **Secure")
    @Post(produces = MediaType.APPLICATION_JSON, consumes = MediaType.APPLICATION_JSON)
    @Secure(Roles.USER)
    TestResult testPromptSystem(@Body TestSuite testSuite) {
        long startTime = System.currentTimeMillis()
        if (testSuite.source != "prompt-tester" || testSuite.kind != "web") {
            log.info("Attempting to test an invalid test suite")
            return createTestResult(testSuite, false, 0, startTime)
        }
        try {
            List<Boolean> results = promptEventTestService.runImmediateChecks()
            boolean didAllTestsPass = results.every { it }
            return createTestResult(testSuite, didAllTestsPass, results.size(), startTime)
        } catch (Exception e) {
            log.warn("Test has failures", e)
        }
        return createTestResult(testSuite, false, PromptEventTestService.IMMEDIATE_TOPICS.size(), startTime)
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
