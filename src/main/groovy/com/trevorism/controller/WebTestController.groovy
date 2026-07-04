package com.trevorism.controller

import com.trevorism.model.EventTestReport
import com.trevorism.model.EventTestRequest
import com.trevorism.secure.Roles
import com.trevorism.secure.Secure
import com.trevorism.service.PromptEventTestService
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Controller("/test")
class WebTestController {

    private final PromptEventTestService promptEventTestService

    WebTestController(PromptEventTestService promptEventTestService) {
        this.promptEventTestService = promptEventTestService
    }

    @Tag(name = "Test Endpoint Operations")
    @Operation(summary = "Validates the prompt event system end-to-end (subscribe, trigger, verify) **Secure")
    @Post(produces = MediaType.APPLICATION_JSON, consumes = MediaType.APPLICATION_JSON)
    @Secure(Roles.USER)
    EventTestReport testPromptSystem(@Body EventTestRequest request) {
        promptEventTestService.runTests(request ?: new EventTestRequest())
    }
}
