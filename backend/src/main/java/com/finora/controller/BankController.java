package com.finora.controller;

import com.finora.accounts.AccountDto;
import com.finora.dto.ApiResponse;
import com.finora.service.BankManagementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Backs the manual "add account" bank picker (Setup.tsx) and anywhere else the frontend needs
 * the full list of banks Finora recognizes, without hardcoding bank names/colors client-side.
 * Not user-scoped -- this is reference data, the same for every user, so no CurrentUser dependency
 * is needed here.
 *
 * Delegates to BankManagementService rather than calling BankRegistry directly (as this did
 * through v48) so admin-added custom banks (V26__custom_banks.sql) show up here too -- see that
 * service's class comment. Note this doesn't extend to CSV bank auto-detection
 * (StatementValidator still calls BankRegistry.detect() directly): detection stays built-in-only,
 * deliberately -- a fuzzy filename/content match against an arbitrary admin-entered alias list
 * risks misdetection in a way manual selection from this list doesn't.
 */
@RestController
@RequestMapping("/api/v1/banks")
public class BankController {

    private final BankManagementService bankManagementService;

    public BankController(BankManagementService bankManagementService) {
        this.bankManagementService = bankManagementService;
    }

    /** q is optional -- omitted (or blank) returns every registered bank, matching the original
     *  behavior this endpoint had before search was added. Powers the "Search Bank" step of
     *  manual account creation, filtering client-side keystrokes against the same registry the
     *  backend uses everywhere else, so there's no separate hardcoded bank list to keep in sync. */
    @GetMapping
    public ApiResponse<List<AccountDto.BankDto>> list(@RequestParam(required = false) String q) {
        return ApiResponse.ok(bankManagementService.search(q));
    }
}
