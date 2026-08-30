package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.InsightsExplorerDto;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.service.InsightsExplorerService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * One user's dashboard insights, traced back to the transaction set and formula that produced
 * each number -- the Insight Explorer, Phase 2's Founder Operations Dashboard (docs/proposals/
 * reconciliation-evolution-roadmap-proposal.md, Part 9). {@code INSIGHTS_EXPLORER_VIEW} is its
 * own permission rather than a reuse of {@code USER_VIEW} or {@code RECONCILIATION_VIEW} -- this
 * is the first admin surface exposing a user's actual computed spend/category/merchant amounts,
 * distinct from both account management and per-transaction reconciliation verdicts.
 */
@RestController
@RequestMapping("/api/v1/admin/insights/explorer")
@PreAuthorize("hasAuthority('INSIGHTS_EXPLORER_VIEW')")
public class AdminInsightsExplorerController {

    private final InsightsExplorerService insightsExplorerService;

    public AdminInsightsExplorerController(InsightsExplorerService insightsExplorerService) {
        this.insightsExplorerService = insightsExplorerService;
    }

    @GetMapping("/{userId}")
    public ApiResponse<InsightsExplorerDto.Trace> trace(@PathVariable UUID userId) {
        return ApiResponse.ok(insightsExplorerService.trace(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }
}
