package com.finora.budgets;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/budgets")
public class BudgetController {

    private final BudgetService budgetService;
    private final CurrentUser currentUser;

    public BudgetController(BudgetService budgetService, CurrentUser currentUser) {
        this.budgetService = budgetService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<BudgetDto>> list() {
        return ApiResponse.ok(budgetService.listForUser(currentUser.id()));
    }

    @PutMapping
    public ApiResponse<BudgetDto> upsert(@Valid @RequestBody BudgetDto.UpsertRequest request) {
        return ApiResponse.ok(budgetService.upsert(currentUser.id(), request), "Budget saved");
    }
}
