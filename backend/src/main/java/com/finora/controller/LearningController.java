package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.LearningDto;
import com.finora.security.CurrentUser;
import com.finora.service.MerchantLearningService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Financial Intelligence Workspace, Learning Engine module -- the cross-merchant views
 *  (timeline, summary) that live here rather than MerchantController because they aren't scoped
 *  to one already-known merchant id. "Reset Learning" itself stays on MerchantController's
 *  POST /merchants/{id}/reset-learning, alongside the existing per-merchant undo -- same resource,
 *  same convention. See MerchantLearningService's own doc comment for what's new here and why
 *  "Disable Learning" isn't. */
@RestController
@RequestMapping("/api/v1/learning")
public class LearningController {

    private final MerchantLearningService merchantLearningService;
    private final CurrentUser currentUser;

    public LearningController(MerchantLearningService merchantLearningService, CurrentUser currentUser) {
        this.merchantLearningService = merchantLearningService;
        this.currentUser = currentUser;
    }

    @GetMapping("/timeline")
    public ApiResponse<List<LearningDto.TimelineEntry>> timeline() {
        return ApiResponse.ok(merchantLearningService.timeline(currentUser.id()));
    }

    @GetMapping("/summary")
    public ApiResponse<LearningDto.Summary> summary() {
        return ApiResponse.ok(merchantLearningService.summary(currentUser.id()));
    }
}
