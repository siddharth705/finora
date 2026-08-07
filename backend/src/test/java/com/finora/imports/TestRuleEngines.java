package com.finora.imports;

import com.finora.service.RuleEngineService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link RuleEngineService} that matches nothing, for the many staging tests that assert parsing
 * and column-detection behaviour and never cared about category rules.
 *
 * <p>Exists because staging now loads the rule set once per statement instead of twice per row (see
 * {@link ImportRuleLookupCountTest}), which added the dependency to {@code TransactionNormalizer},
 * {@code PreviewGenerator} and {@code PdfPreviewGenerator}. Those tests previously got an empty
 * rule set implicitly, from a mocked {@code CategorizationService} that returned a fixed
 * suggestion; keeping that behaviour explicit in one shared place is better than 25 copies of the
 * same two-line stub, and means a future change to the collaborator is a single edit.
 *
 * <p>Deliberately not a no-op implementation class: a mock records interactions, so a test that
 * later wants to assert how often the rule set was loaded can do so without rewiring.
 */
public final class TestRuleEngines {

    private TestRuleEngines() {}

    /** A rule engine holding no rules, so every row falls through to whatever the mocked
     *  categorization collaborator returns -- exactly the behaviour these tests had before the
     *  rule set became an explicit constructor dependency. */
    public static RuleEngineService empty() {
        RuleEngineService ruleEngineService = mock(RuleEngineService.class);
        when(ruleEngineService.ruleSet(any())).thenReturn(List.of());
        return ruleEngineService;
    }
}
