package com.finora.rules;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** User-authored category rules, evaluated by RuleEngineService ahead of the learned/keyword
 *  fallback in CategorizationService.suggest() -- see docs/rule-engine-relationship-engine-eds.md.
 *  Thin by convention (BudgetController, RoleAdminController, ...): all logic lives in RuleService. */
@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private final RuleService ruleService;
    private final CurrentUser currentUser;

    public RuleController(RuleService ruleService, CurrentUser currentUser) {
        this.ruleService = ruleService;
        this.currentUser = currentUser;
    }

    // Includes read-only global rules alongside the caller's own -- see RuleService.listForUser.
    @GetMapping
    public ApiResponse<List<RuleDto>> list() {
        return ApiResponse.ok(ruleService.listForUser(currentUser.id()));
    }

    @PostMapping
    public ApiResponse<RuleDto> create(@Valid @RequestBody RuleDto.CreateRequest request) {
        return ApiResponse.ok(ruleService.create(currentUser.id(), request), "Rule created");
    }

    @PutMapping("/{id}")
    public ApiResponse<RuleDto> update(@PathVariable UUID id, @RequestBody RuleDto.UpdateRequest request) {
        return ApiResponse.ok(ruleService.update(currentUser.id(), id, request), "Rule updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        ruleService.delete(currentUser.id(), id);
        return ApiResponse.ok(null, "Rule deleted");
    }
}
