package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.transactions.TransactionDto;
import com.finora.transactions.TransactionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Support-assisted transaction visibility + deletion for a specific user (TRANSACTION_DELETE,
 * V16__rbac_roles_permissions.sql). Reuses TransactionService.search/delete exactly as the
 * self-service Ledger does -- same TransactionDto shape, same categorization/reconciliation
 * detail -- just with the target userId sourced from the path instead of CurrentUser.
 *
 * TRANSACTION_IMPORT (an admin running a CSV import on a user's behalf) is deliberately NOT
 * covered here -- CsvImportService's staging/review/confirm workflow is a substantial multi-step
 * flow built entirely around the importing user's own session end-to-end; replicating it for an
 * admin acting on someone else's account is real, separate scope, not a thin wrapper like the
 * rest of this controller. Left for a future pass.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/transactions")
@PreAuthorize("hasAuthority('TRANSACTION_DELETE')")
public class AdminTransactionController {

    private final TransactionService transactionService;

    public AdminTransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /** Most recent 50 transactions -- enough for an admin to find the one they're looking for
     *  without building out a second full filter UI just for this support path (the self-service
     *  Ledger's own multi-filter search already exists for anyone who needs that on their own
     *  data). */
    @GetMapping
    public ApiResponse<List<TransactionDto>> list(@PathVariable UUID userId) {
        var filter = new TransactionDto.FilterRequest(null, null, null, null, null, null, null, null,
                0, 50, "txnDate", "DESC");
        return ApiResponse.ok(transactionService.search(userId, filter));
    }

    @DeleteMapping("/{transactionId}")
    public ApiResponse<Void> delete(@PathVariable UUID userId, @PathVariable UUID transactionId) {
        transactionService.delete(userId, transactionId);
        return ApiResponse.ok(null, "Transaction deleted");
    }
}
