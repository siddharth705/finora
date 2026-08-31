package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.CoverageDto;
import com.finora.service.AccountCoverageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The admin half of docs/proposals/statement-continuity-and-coverage-integrity-proposal.md's
 * Phase 1 (§0.14) -- extends the roadmap doc's planned Import Explorer, not a new admin
 * subsystem. Deliberately the first consumer of the coverage engine to ship, ahead of any
 * consumer-facing surface: someone needs to watch it run against real accounts (PNB's boundary-day
 * convention, Kotak, SBI, credit-card cycles) before a false positive reaches a user.
 *
 * <p>{@code PLATFORM_DIAGNOSTICS_VIEW}, the same read-only permission {@link
 * AdminImportTraceController} uses -- looking up by {@code accountId} alone needs no target
 * {@code userId} in the path, matching that controller's own by-reference lookup shape.
 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminAccountCoverageController {

    private final AccountCoverageService accountCoverageService;

    public AdminAccountCoverageController(AccountCoverageService accountCoverageService) {
        this.accountCoverageService = accountCoverageService;
    }

    @GetMapping("/{accountId}/coverage")
    public ApiResponse<CoverageDto> coverage(@PathVariable UUID accountId) {
        return ApiResponse.ok(accountCoverageService.forAccountAsAdmin(accountId));
    }
}
