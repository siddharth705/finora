package com.finora.rules;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** scope/field/operator/actionType travel as plain strings (enum name()) — same convention as
 *  TransactionDto's type/txnType fields — so the frontend doesn't need a parallel Java-enum-aware
 *  client type.
 *
 *  matchCount/lastMatchedAt: Financial Intelligence Workspace, Rule Management module -- see
 *  RuleEngineService.recordMatch's doc comment for exactly when these increment. For a GLOBAL
 *  rule this is a count across every user (one shared row), not just the caller's own matches. */
public record RuleDto(
        UUID id, String scope, String field, String operator, String comparisonValue,
        String actionType, String actionValue, int priority, boolean enabled,
        long matchCount, Instant lastMatchedAt
) {
    // Always creates a USER-scope rule — see RuleService.create(). GLOBAL rules are seed data
    // only for this milestone (docs/rule-engine-relationship-engine-eds.md §6 Non-goals).
    public record CreateRequest(
            @NotNull(message = "Field is required") String field,
            @NotNull(message = "Operator is required") String operator,
            @NotBlank(message = "Comparison value is required") String comparisonValue,
            @NotNull(message = "Action type is required") String actionType,
            String actionValue,
            Integer priority
    ) {}

    /** Every field optional -- only supplied ones change, same partial-update convention as
     *  TransactionDto.UpdateRequest. */
    public record UpdateRequest(
            String field, String operator, String comparisonValue,
            String actionType, String actionValue, Integer priority, Boolean enabled
    ) {}

    /** Admin Rule Engine module -- "would this rule match?" against sample transaction fields,
     *  without creating or persisting anything. field/operator/comparisonValue mirror
     *  CreateRequest's shape so the same in-progress (possibly unsaved) form values can be tested
     *  before the admin commits to saving them. The four sample* fields are deliberately all
     *  optional/nullable -- RuleEngineService.matches() already fails closed (no match) on a null
     *  field it needs, exactly like a real transaction missing that data would. */
    public record TestRequest(
            @NotNull(message = "Field is required") String field,
            @NotNull(message = "Operator is required") String operator,
            @NotBlank(message = "Comparison value is required") String comparisonValue,
            String sampleDescription,
            BigDecimal sampleAmount,
            String sampleMerchant,
            String sampleAccountType
    ) {}

    public record TestResult(boolean matches) {}
}
