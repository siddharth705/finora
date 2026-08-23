package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.rules.RuleDto;
import com.finora.rules.RuleService;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Support-assisted personal rule management for a specific user (RULE_MANAGE,
 * V25__rule_manage_permission.sql -- reused rather than a new permission, since this is the same
 * "manage rules" capability RuleService already gates GLOBAL authoring behind). Reuses
 * RuleService.listForUser/create/update/delete exactly as the self-service Rules page did before
 * it was folded into the automatic "remember this choice" flow on the Transactions page -- same
 * RuleDto shape, same USER-scope validation, same ownership checks (RuleService
 * .getOwnedUserRule() already rejects a GLOBAL rule id or one belonging to a different user, so
 * this path can't be used to reach anything the target userId doesn't itself own). Same thin-proxy
 * pattern as AdminTransactionController / AdminUserMerchantController.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/rules")
@PreAuthorize("hasAuthority('RULE_MANAGE')")
public class AdminUserRuleController {

    private final RuleService ruleService;
    private final CurrentUser currentUser;

    public AdminUserRuleController(RuleService ruleService, CurrentUser currentUser) {
        this.ruleService = ruleService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<RuleDto>> list(@PathVariable UUID userId) {
        return ApiResponse.ok(ruleService.listForUser(userId));
    }

    @PostMapping
    public ApiResponse<RuleDto> create(@PathVariable UUID userId, @Valid @RequestBody RuleDto.CreateRequest request) {
        return ApiResponse.ok(ruleService.create(userId, request, currentUser.id()), "Rule created");
    }

    @PutMapping("/{id}")
    public ApiResponse<RuleDto> update(@PathVariable UUID userId, @PathVariable UUID id,
                                        @Valid @RequestBody RuleDto.UpdateRequest request) {
        return ApiResponse.ok(ruleService.update(userId, id, request, currentUser.id()), "Rule updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID userId, @PathVariable UUID id) {
        ruleService.delete(userId, id, currentUser.id());
        return ApiResponse.ok(null, "Rule deleted");
    }
}
