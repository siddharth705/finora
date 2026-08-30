package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.CoverageDto;
import com.finora.security.CurrentUser;
import com.finora.service.AccountCoverageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 1 of docs/proposals/statement-continuity-and-coverage-integrity-proposal.md.
 * Deliberately no other user-facing surface yet (§11) -- this endpoint exists to be observed via
 * the admin coverage view ({@link AdminAccountCoverageController}) before anything reads it in a
 * consumer-facing screen.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountCoverageController {

    private final AccountCoverageService accountCoverageService;
    private final CurrentUser currentUser;

    public AccountCoverageController(AccountCoverageService accountCoverageService, CurrentUser currentUser) {
        this.accountCoverageService = accountCoverageService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{accountId}/coverage")
    public ApiResponse<CoverageDto> coverage(@PathVariable UUID accountId) {
        return ApiResponse.ok(accountCoverageService.forAccount(currentUser.id(), accountId));
    }
}
