package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.rules.RuleDto;
import com.finora.security.CurrentUser;
import com.finora.service.RuleEngineService;
import com.finora.rules.RuleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin management of GLOBAL-scope category rules -- rules that apply to every user's
 * auto-categorization, not just one account's. Previously seed-data-only (see RuleService's class
 * comment); this is the RULE_MANAGE-gated console that was always the intended fast-follow
 * (docs/rule-engine-relationship-engine-eds.md §6 Non-goals).
 *
 * Deliberately a separate controller from RuleController rather than an admin-only branch of it --
 * same reasoning as AdminUserController vs UserController: different permission gate, different
 * scope (every user's data vs. the caller's own), same convention this codebase already follows.
 */
@RestController
@RequestMapping("/api/v1/admin/rules")
@PreAuthorize("hasAuthority('RULE_MANAGE')")
public class AdminRuleController {

    private final RuleService ruleService;
    private final RuleEngineService ruleEngineService;
    private final CurrentUser currentUser;

    public AdminRuleController(RuleService ruleService, RuleEngineService ruleEngineService, CurrentUser currentUser) {
        this.ruleService = ruleService;
        this.ruleEngineService = ruleEngineService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<RuleDto>> list() {
        return ApiResponse.ok(ruleService.listGlobal());
    }

    @PostMapping
    public ApiResponse<RuleDto> create(@Valid @RequestBody RuleDto.CreateRequest request) {
        return ApiResponse.ok(ruleService.createGlobal(currentUser.id(), request), "Global rule created");
    }

    @PutMapping("/{id}")
    public ApiResponse<RuleDto> update(@PathVariable UUID id, @Valid @RequestBody RuleDto.UpdateRequest request) {
        return ApiResponse.ok(ruleService.updateGlobal(currentUser.id(), id, request), "Global rule updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        ruleService.deleteGlobal(currentUser.id(), id);
        return ApiResponse.ok(null, "Global rule deleted");
    }

    // Rule Engine module: "would this rule match?" against sample fields, without creating or
    // persisting anything -- see RuleEngineService.testMatch()'s doc comment. Deliberately not
    // scoped to an existing rule id: this is meant to work on in-progress, possibly-unsaved form
    // values, whether authoring a brand new rule or editing one that hasn't been saved yet.
    @PostMapping("/test")
    public ApiResponse<RuleDto.TestResult> test(@Valid @RequestBody RuleDto.TestRequest request) {
        boolean matches = ruleEngineService.testMatch(
                request.field(), request.operator(), request.comparisonValue(),
                request.sampleDescription(), request.sampleAmount(), request.sampleMerchant(), request.sampleAccountType());
        return ApiResponse.ok(new RuleDto.TestResult(matches));
    }
}
