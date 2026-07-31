package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.MerchantDto;
import com.finora.transactions.TransactionDto;
import com.finora.security.CurrentUser;
import com.finora.service.MerchantLearningService;
import com.finora.service.MerchantService;
import com.finora.transactions.TransactionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Merchant Management API -- list/detail/audit-history/rename/merge/confirm-category/undo, per
 *  docs/financial-intelligence-engine-spec.md §5.1-5.6.
 *  Thin by convention (RuleController, RelationshipController, ...): all logic lives in
 *  MerchantService, TransactionService (confirm-category's transaction-side update), and
 *  MerchantLearningService (undo). */
@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final MerchantService merchantService;
    private final MerchantLearningService merchantLearningService;
    private final TransactionService transactionService;
    private final CurrentUser currentUser;

    public MerchantController(MerchantService merchantService, MerchantLearningService merchantLearningService,
                               TransactionService transactionService, CurrentUser currentUser) {
        this.merchantService = merchantService;
        this.merchantLearningService = merchantLearningService;
        this.transactionService = transactionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<MerchantDto>> list() {
        return ApiResponse.ok(merchantService.listForUser(currentUser.id()));
    }

    @GetMapping("/{id}")
    public ApiResponse<MerchantDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(merchantService.get(currentUser.id(), id));
    }

    @GetMapping("/{id}/audit")
    public ApiResponse<List<MerchantDto.AuditEntry>> audit(@PathVariable UUID id) {
        return ApiResponse.ok(merchantService.auditHistory(currentUser.id(), id));
    }

    // Financial Intelligence Workspace, Module 2 -- backs the Merchant Management console's
    // transaction-history tab. See MerchantService.transactionsFor's doc comment.
    @GetMapping("/{id}/transactions")
    public ApiResponse<List<TransactionDto>> transactions(@PathVariable UUID id) {
        return ApiResponse.ok(merchantService.transactionsFor(currentUser.id(), id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<MerchantDto> update(@PathVariable UUID id, @RequestBody MerchantDto.UpdateRequest request) {
        return ApiResponse.ok(merchantService.rename(currentUser.id(), id, request), "Merchant updated");
    }

    @PostMapping("/{id}/merge")
    public ApiResponse<MerchantDto> merge(@PathVariable UUID id, @Valid @RequestBody MerchantDto.MergeRequest request) {
        return ApiResponse.ok(merchantService.merge(currentUser.id(), id, request.mergeFromMerchantId()), "Merchants merged");
    }

    // §5.5 -- replaces PATCH /transactions/{id}/category for transactions resolved to a real
    // merchant; see TransactionService.confirmMerchantCategory's doc comment for why the
    // learning-side wiring (categorizationService.learn() -> MerchantLearningService.confirm())
    // was already in place and this endpoint is a shape adapter, not new business logic.
    @PostMapping("/{merchantId}/confirm-category")
    public ApiResponse<MerchantDto> confirmCategory(@PathVariable UUID merchantId,
                                                      @Valid @RequestBody MerchantDto.ConfirmCategoryRequest request) {
        transactionService.confirmMerchantCategory(currentUser.id(), merchantId, request.applyToTransactionId(), request.categoryId());
        return ApiResponse.ok(merchantService.get(currentUser.id(), merchantId), "Category confirmed");
    }

    // §5.6 -- MerchantLearningService.undo() already exists and is tested
    // (MerchantLearningServiceTest); this is the first controller wiring it up.
    @PostMapping("/{id}/undo")
    public ApiResponse<MerchantDto> undo(@PathVariable UUID id) {
        merchantLearningService.undo(currentUser.id(), id);
        return ApiResponse.ok(merchantService.get(currentUser.id(), id), "Last learning event undone");
    }

    // Financial Intelligence Workspace, Learning Engine module -- see
    // MerchantLearningService.reset()'s own doc comment for how this differs from undo().
    @PostMapping("/{id}/reset-learning")
    public ApiResponse<MerchantDto> resetLearning(@PathVariable UUID id) {
        merchantLearningService.reset(currentUser.id(), id);
        return ApiResponse.ok(merchantService.get(currentUser.id(), id), "Learning reset for this merchant");
    }
}
