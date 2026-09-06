package com.finora.support;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Feedback submission. D1: authentication is required in v1 — see {@code FeedbackEntry.userId}'s
 *  own doc for why the column stays nullable regardless. */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final CurrentUser currentUser;

    public FeedbackController(FeedbackService feedbackService, CurrentUser currentUser) {
        this.feedbackService = feedbackService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ApiResponse<FeedbackDto.Summary> submit(@Valid @RequestBody FeedbackDto.CreateRequest request) {
        return ApiResponse.ok(
                feedbackService.submit(currentUser.id(), request.type(), request.context(), request.message()),
                "Thanks for the feedback");
    }
}
