package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.LearningDto;
import com.finora.service.MerchantLearningService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Support-assisted Learning Engine visibility for a specific user (MERCHANT_MANAGE -- reused
 * rather than a new permission, same reasoning as AdminLearningStatsController). Read-only proxy
 * of LearningController's timeline()/summary(), userId sourced from the path instead of
 * CurrentUser -- same thin-proxy pattern as AdminTransactionController / AdminUserMerchantController
 * / AdminUserRuleController. Deliberately doesn't proxy confirm()/undo()/reset() (those live on
 * MerchantController, already excluded from the admin surface by AdminUserMerchantController's
 * own doc comment for the same "make sense in the context of a user reviewing their own
 * transaction" reasoning) -- this is purely "what has this account's Learning Engine done,"
 * not a way to act on it from the admin console.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/learning")
@PreAuthorize("hasAuthority('MERCHANT_MANAGE')")
public class AdminUserLearningController {

    private final MerchantLearningService merchantLearningService;

    public AdminUserLearningController(MerchantLearningService merchantLearningService) {
        this.merchantLearningService = merchantLearningService;
    }

    @GetMapping("/timeline")
    public ApiResponse<List<LearningDto.TimelineEntry>> timeline(@PathVariable UUID userId) {
        return ApiResponse.ok(merchantLearningService.timeline(userId));
    }

    @GetMapping("/summary")
    public ApiResponse<LearningDto.Summary> summary(@PathVariable UUID userId) {
        return ApiResponse.ok(merchantLearningService.summary(userId));
    }
}
