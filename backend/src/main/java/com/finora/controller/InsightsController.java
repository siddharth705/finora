package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.InsightsDto;
import com.finora.security.CurrentUser;
import com.finora.service.InsightsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insights")
public class InsightsController {

    private final InsightsService insightsService;
    private final CurrentUser currentUser;

    public InsightsController(InsightsService insightsService, CurrentUser currentUser) {
        this.insightsService = insightsService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<InsightsDto> insights() {
        return ApiResponse.ok(insightsService.build(currentUser.id()));
    }
}
