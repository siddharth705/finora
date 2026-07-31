package com.finora.controller;

import com.finora.dto.AdminDtos.LearningPlatformStatsDto;
import com.finora.dto.ApiResponse;
import com.finora.service.AdminLearningStatsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform-wide Learning Engine stats for the admin console (frontend-admin/). Reuses
 *  MERCHANT_MANAGE rather than a new permission -- Learning Engine data is the same underlying
 *  merchant-category-confirmation domain Merchant Intelligence already gates, not a separate
 *  capability (same reasoning AdminUserRuleController used to reuse RULE_MANAGE). Per-user
 *  learning visibility (a specific account's timeline/summary) is a separate controller,
 *  AdminUserLearningController -- this one is read-only and never scoped to one user. */
@RestController
@RequestMapping("/api/v1/admin/learning")
@PreAuthorize("hasAuthority('MERCHANT_MANAGE')")
public class AdminLearningStatsController {

    private final AdminLearningStatsService adminLearningStatsService;

    public AdminLearningStatsController(AdminLearningStatsService adminLearningStatsService) {
        this.adminLearningStatsService = adminLearningStatsService;
    }

    @GetMapping("/stats")
    public ApiResponse<LearningPlatformStatsDto> stats() {
        return ApiResponse.ok(adminLearningStatsService.platformStats());
    }
}
