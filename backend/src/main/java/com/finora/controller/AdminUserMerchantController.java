package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.MerchantDto;
import com.finora.transactions.TransactionDto;
import com.finora.service.MerchantService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Support-assisted merchant management for a specific user (MERCHANT_MANAGE,
 * V28__merchant_manage_permission.sql). Reuses MerchantService.listForUser/get/auditHistory/
 * transactionsFor/rename/merge exactly as the self-service Merchant Management console does --
 * same MerchantDto shape, same merge/rename/audit semantics -- just with the target userId
 * sourced from the path instead of CurrentUser. Same "thin proxy" pattern as
 * AdminTransactionController.
 *
 * confirm-category/undo/reset-learning (MerchantController's other three endpoints) are
 * deliberately NOT mirrored here -- those apply a specific category choice or roll back a
 * specific learning event, which only makes sense in the context of the user actually reviewing
 * their own transaction, not an admin acting on a name/duplicate-cleanup basis. Left out rather
 * than built as an unused surface.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/merchants")
@PreAuthorize("hasAuthority('MERCHANT_MANAGE')")
public class AdminUserMerchantController {

    private final MerchantService merchantService;

    public AdminUserMerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @GetMapping
    public ApiResponse<List<MerchantDto>> list(@PathVariable UUID userId) {
        return ApiResponse.ok(merchantService.listForUser(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<MerchantDto> get(@PathVariable UUID userId, @PathVariable UUID id) {
        return ApiResponse.ok(merchantService.get(userId, id));
    }

    @GetMapping("/{id}/audit")
    public ApiResponse<List<MerchantDto.AuditEntry>> audit(@PathVariable UUID userId, @PathVariable UUID id) {
        return ApiResponse.ok(merchantService.auditHistory(userId, id));
    }

    @GetMapping("/{id}/transactions")
    public ApiResponse<List<TransactionDto>> transactions(@PathVariable UUID userId, @PathVariable UUID id) {
        return ApiResponse.ok(merchantService.transactionsFor(userId, id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<MerchantDto> update(@PathVariable UUID userId, @PathVariable UUID id,
                                            @RequestBody MerchantDto.UpdateRequest request) {
        return ApiResponse.ok(merchantService.rename(userId, id, request), "Merchant updated");
    }

    @PostMapping("/{id}/merge")
    public ApiResponse<MerchantDto> merge(@PathVariable UUID userId, @PathVariable UUID id,
                                           @Valid @RequestBody MerchantDto.MergeRequest request) {
        return ApiResponse.ok(merchantService.merge(userId, id, request.mergeFromMerchantId()), "Merchants merged");
    }
}
