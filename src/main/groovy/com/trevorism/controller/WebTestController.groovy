package com.trevorism.controller

import com.trevorism.model.TestResult
import com.trevorism.model.TestSuite
import com.trevorism.secure.Roles
import com.trevorism.secure.Secure
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

    @Tag(name = "Test Endpoint Operations")
    @Operation(summary = "Tests event alerting system **Secure")
    @Post(produces = MediaType.APPLICATION_JSON, consumes = MediaType.APPLICATION_JSON)
    @Secure(Roles.USER)
    TestResult testPromptSystem(@Body TestSuite testSuite) {
        //Start a millisecond timer
        long startTime = System.currentTimeMillis()
        def allTestResults = []
        try {
            //Put tests here

            boolean didAllTestsPass = allTestResults.every { it }
            return createTestResult(testSuite, didAllTestsPass, allTestResults.size(), startTime)
        } catch (Exception e) {
            log.warn("Test has failures", e)

        }
        return createTestResult(testSuite, false, allTestResults.size(), startTime)
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
