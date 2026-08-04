package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.MerchantDto;
import com.finora.security.CurrentUser;
import com.finora.service.MerchantLearningService;
import com.finora.transactions.TransactionDto;
import com.finora.service.MerchantService;
import com.finora.transactions.TransactionService;
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
 * confirm-category/undo/reset-learning were originally left out here because they only made
 * sense in the context of the user actually reviewing their own transaction. That assumption no
 * longer holds: MerchantController (the self-service counterpart) has been retired entirely, so
 * these are now the only way anyone -- including the account's own owner -- can apply a category
 * choice or roll back a learning event. Mirrored here with the same MerchantLearningService/
 * TransactionService calls, just with userId sourced from the path.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/merchants")
@PreAuthorize("hasAuthority('MERCHANT_MANAGE')")
public class AdminUserMerchantController {

    private final MerchantService merchantService;
    private final MerchantLearningService merchantLearningService;
    private final TransactionService transactionService;
    private final CurrentUser currentUser;

    public AdminUserMerchantController(MerchantService merchantService, MerchantLearningService merchantLearningService,
                                        TransactionService transactionService, CurrentUser currentUser) {
        this.merchantService = merchantService;
        this.merchantLearningService = merchantLearningService;
        this.transactionService = transactionService;
        this.currentUser = currentUser;
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
                                            @Valid @RequestBody MerchantDto.UpdateRequest request) {
        return ApiResponse.ok(merchantService.rename(userId, id, request, currentUser.id()), "Merchant updated");
    }

    @PostMapping("/{id}/merge")
    public ApiResponse<MerchantDto> merge(@PathVariable UUID userId, @PathVariable UUID id,
                                           @Valid @RequestBody MerchantDto.MergeRequest request) {
        return ApiResponse.ok(merchantService.merge(userId, id, request.mergeFromMerchantId(), currentUser.id()), "Merchants merged");
    }

    @PostMapping("/{merchantId}/confirm-category")
    public ApiResponse<MerchantDto> confirmCategory(@PathVariable UUID userId, @PathVariable UUID merchantId,
                                                      @Valid @RequestBody MerchantDto.ConfirmCategoryRequest request) {
        transactionService.confirmMerchantCategory(userId, merchantId, request.applyToTransactionId(), request.categoryId(),
                currentUser.id());
        return ApiResponse.ok(merchantService.get(userId, merchantId), "Category confirmed");
    }

    @PostMapping("/{id}/undo")
    public ApiResponse<MerchantDto> undo(@PathVariable UUID userId, @PathVariable UUID id) {
        merchantLearningService.undo(userId, id, currentUser.id());
        return ApiResponse.ok(merchantService.get(userId, id), "Last learning event undone");
    }

    @PostMapping("/{id}/reset-learning")
    public ApiResponse<MerchantDto> resetLearning(@PathVariable UUID userId, @PathVariable UUID id) {
        merchantLearningService.reset(userId, id, currentUser.id());
        return ApiResponse.ok(merchantService.get(userId, id), "Learning reset for this merchant");
    }
}
