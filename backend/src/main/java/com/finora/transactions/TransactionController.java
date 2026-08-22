package com.finora.transactions;

import com.finora.dto.ApiResponse;
import com.finora.dto.PagedResponse;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final TransactionExplanationService explanationService;
    private final CurrentUser currentUser;

    public TransactionController(TransactionService transactionService,
                                  TransactionExplanationService explanationService,
                                  CurrentUser currentUser) {
        this.transactionService = transactionService;
        this.explanationService = explanationService;
        this.currentUser = currentUser;
    }

    /** Backs the Ledger page's filter bar — every query param is optional. */
    @GetMapping
    public ApiResponse<PagedResponse<TransactionDto>> search(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) BigDecimal amountMin,
            @RequestParam(required = false) BigDecimal amountMax,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "date") String sortField,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        var filter = new TransactionDto.FilterRequest(accountId, categoryId, type, dateFrom, dateTo,
                amountMin, amountMax, keyword, page, size, sortField, sortDir);
        return ApiResponse.ok(transactionService.search(currentUser.id(), filter));
    }

    /** Backs the "Ask Once, Learn Forever" review queue — see Dashboard's review card. */
    @GetMapping("/needs-review")
    public ApiResponse<List<TransactionDto>> needsReview() {
        return ApiResponse.ok(transactionService.needsReview(currentUser.id()));
    }

    /** "Why this category?" — fetched on demand, not on every row of the Ledger's list. */
    @GetMapping("/{id}/explanation")
    public ApiResponse<TransactionExplanationDto> explanation(@PathVariable UUID id) {
        return ApiResponse.ok(explanationService.explain(currentUser.id(), id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TransactionDto>> create(@Valid @RequestBody TransactionDto.CreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(transactionService.create(currentUser.id(), request), "Transaction created"));
    }

    @PatchMapping("/{id}/category")
    public ResponseEntity<ApiResponse<TransactionDto>> updateCategory(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok(transactionService.updateCategory(currentUser.id(), id, body.get("category")), "Category updated"));
    }

    /** Full edit — the Transactions page's Edit action. See TransactionDto.UpdateRequest for
     *  exactly which fields this covers (everything except which account it belongs to). */
    /**
     * BH-027. "No, these really are two separate transactions."
     *
     * <p>POST rather than PATCH: this records a decision the user made, it does not edit a field
     * they chose the value of. The response carries the transaction as it now stands so the row
     * can be re-rendered without a refetch.
     */
    @PostMapping("/{id}/not-duplicate")
    public ResponseEntity<ApiResponse<TransactionDto>> confirmNotDuplicate(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(
                transactionService.confirmNotDuplicate(currentUser.id(), id),
                "Kept as a separate transaction"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionDto>> update(
            @PathVariable UUID id, @Valid @RequestBody TransactionDto.UpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(transactionService.update(currentUser.id(), id, request), "Transaction updated"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        transactionService.delete(currentUser.id(), id, currentUser.id());
        return ResponseEntity.ok(ApiResponse.ok(null, "Transaction deleted"));
    }

    @PostMapping("/bulk-delete")
    public ResponseEntity<ApiResponse<Void>> bulkDelete(@Valid @RequestBody TransactionDto.BulkDeleteRequest request) {
        transactionService.bulkDelete(currentUser.id(), request.ids(), currentUser.id());
        return ResponseEntity.ok(ApiResponse.ok(null, request.ids().size() + " transaction(s) deleted"));
    }

    @PostMapping("/bulk-category")
    public ResponseEntity<ApiResponse<Void>> bulkRecategorize(
            @Valid @RequestBody TransactionDto.BulkRecategorizeRequest request) {
        transactionService.bulkRecategorize(currentUser.id(), request.ids(), request.category(), currentUser.id());
        return ResponseEntity.ok(ApiResponse.ok(null, request.ids().size() + " transaction(s) recategorized"));
    }
}
