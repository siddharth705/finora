package com.finora.controller;

import com.finora.accounts.AccountDto;
import com.finora.dto.ApiResponse;
import com.finora.accounts.AccountService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Support-assisted account management -- an admin viewing/creating/editing/deleting a specific
 * user's financial accounts on their behalf (ACCOUNT_CREATE/ACCOUNT_UPDATE/ACCOUNT_DELETE,
 * V16__rbac_roles_permissions.sql). Deliberately thin: AccountService.create/update/delete already
 * take the target userId as an explicit parameter (they were never hardcoded to "the caller's own
 * data"), so this is a straight pass-through with the userId sourced from the path instead of
 * CurrentUser -- no service-layer changes needed to support this.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/accounts")
public class AdminAccountController {

    private final AccountService accountService;

    public AdminAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public ApiResponse<List<AccountDto>> list(@PathVariable UUID userId) {
        return ApiResponse.ok(accountService.listForUser(userId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ACCOUNT_CREATE')")
    public ApiResponse<AccountDto> create(@PathVariable UUID userId, @RequestBody AccountDto.CreateRequest request) {
        return ApiResponse.ok(accountService.create(userId, request), "Account created");
    }

    @PutMapping("/{accountId}")
    @PreAuthorize("hasAuthority('ACCOUNT_UPDATE')")
    public ApiResponse<AccountDto> update(@PathVariable UUID userId, @PathVariable UUID accountId,
                                           @RequestBody AccountDto.CreateRequest request) {
        return ApiResponse.ok(accountService.update(userId, accountId, request), "Account updated");
    }

    @DeleteMapping("/{accountId}")
    @PreAuthorize("hasAuthority('ACCOUNT_DELETE')")
    public ApiResponse<Void> delete(@PathVariable UUID userId, @PathVariable UUID accountId) {
        accountService.delete(userId, accountId);
        return ApiResponse.ok(null, "Account deleted");
    }
}
