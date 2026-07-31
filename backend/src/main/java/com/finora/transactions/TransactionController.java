package com.finora.transactions;

import com.finora.dto.ApiResponse;
import com.finora.dto.PagedResponse;
import com.finora.security.CurrentUser;
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
    private final CurrentUser currentUser;

    public TransactionController(TransactionService transactionService, CurrentUser currentUser) {
        this.transactionService = transactionService;
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

    @PostMapping
    public ResponseEntity<ApiResponse<TransactionDto>> create(@RequestBody TransactionDto.CreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(transactionService.create(currentUser.id(), request), "Transaction created"));
    }

    @PatchMapping("/{id}/category")
    public ResponseEntity<ApiResponse<TransactionDto>> updateCategory(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok(transactionService.updateCategory(currentUser.id(), id, body.get("category")), "Category updated"));
    }

    /** Full edit — the Transactions page's Edit action. See TransactionDto.UpdateRequest for
     *  exactly which fields this covers (everything except which account it belongs to). */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionDto>> update(
            @PathVariable UUID id, @RequestBody TransactionDto.UpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(transactionService.update(currentUser.id(), id, request), "Transaction updated"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        transactionService.delete(currentUser.id(), id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Transaction deleted"));
    }

    @PostMapping("/bulk-delete")
    public ResponseEntity<ApiResponse<Void>> bulkDelete(@RequestBody List<UUID> ids) {
        transactionService.bulkDelete(currentUser.id(), ids);
        return ResponseEntity.ok(ApiResponse.ok(null, ids.size() + " transaction(s) deleted"));
    }

    @PostMapping("/bulk-category")
    public ResponseEntity<ApiResponse<Void>> bulkRecategorize(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> idStrings = (List<String>) body.get("ids");
        List<UUID> ids = idStrings.stream().map(UUID::fromString).toList();
        transactionService.bulkRecategorize(currentUser.id(), ids, (String) body.get("category"));
        return ResponseEntity.ok(ApiResponse.ok(null, ids.size() + " transaction(s) recategorized"));
    }
}
