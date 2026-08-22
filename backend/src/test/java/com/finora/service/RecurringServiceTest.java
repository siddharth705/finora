package com.finora.service;

import com.finora.entity.CategoryRule;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class RecurringServiceTest {

    private TransactionRepository transactionRepository;
    private RuleEngineService ruleEngineService;
    private AuditService auditService;
    private FeatureFlagService featureFlagService;
    private RecurringService recurringService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        // Unstubbed -- Mockito defaults a List-returning method to an empty list, so every
        // existing test here (none of which are about rule-driven subscriptions -- see the
        // dedicated tests below) is unaffected by this dependency's addition.
        ruleEngineService = mock(RuleEngineService.class);
        auditService = mock(AuditService.class);
        featureFlagService = mock(FeatureFlagService.class);
        // Admin Portal Phase 8 -- default the flag on so every pre-existing test here keeps
        // exercising the real detection logic unchanged; the flag-off behavior gets its own
        // dedicated tests below.
        when(featureFlagService.isEnabled("RECURRING_DETECTION_ENABLED")).thenReturn(true);
        recurringService = new RecurringService(transactionRepository, ruleEngineService, auditService,
                featureFlagService);
    }

    private Transaction expense(String merchant, LocalDate date, BigDecimal amount) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setMerchant(merchant);
        t.setTxnDate(date);
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        return t;
    }

    @Test
    void detectsMonthlySubscriptionWithConsistentAmountAndSpacing() {
        List<Transaction> txns = List.of(
                expense("netflix", LocalDate.of(2026, 5, 5), BigDecimal.valueOf(649)),
                expense("netflix", LocalDate.of(2026, 6, 5), BigDecimal.valueOf(649)),
                expense("netflix", LocalDate.of(2026, 7, 6), BigDecimal.valueOf(649))
        );
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        var results = recurringService.detectForUser(userId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).merchant()).isEqualTo("netflix");
        assertThat(results.get(0).label()).isEqualTo("Monthly");
        assertThat(txns).allMatch(Transaction::isRecurring);
    }

    @Test
    void doesNotFlagAOneOffPurchase() {
        List<Transaction> txns = List.of(
                expense("amazon", LocalDate.of(2026, 6, 1), BigDecimal.valueOf(1200))
        );
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        var results = recurringService.detectForUser(userId);

        assertThat(results).isEmpty();
        assertThat(txns.get(0).isRecurring()).isFalse();
    }

    /**
     * Bug fix: transactions with no merchant identified used to all get bucketed together under
     * the literal string "unknown" for pattern grouping -- so two entirely UNRELATED transactions
     * with no merchant (manual cash entries, say) that happen to land at a roughly regular
     * interval with a similar amount could get falsely flagged as a recurring merchant literally
     * named "unknown". There's no real merchant pattern to detect without a merchant at all, so
     * these must be excluded from grouping entirely rather than defaulting into a shared bucket.
     */
    @Test
    void doesNotFalselyGroupUnrelatedTransactionsWithNoMerchantUnderAFakeUnknownMerchant() {
        List<Transaction> txns = List.of(
                expense(null, LocalDate.of(2026, 5, 5), BigDecimal.valueOf(500)),
                expense(null, LocalDate.of(2026, 6, 4), BigDecimal.valueOf(500)),
                expense("", LocalDate.of(2026, 7, 5), BigDecimal.valueOf(510))
        );
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        var results = recurringService.detectForUser(userId);

        assertThat(results).isEmpty();
        assertThat(txns).noneMatch(Transaction::isRecurring);
    }

    @Test
    void doesNotFlagIrregularSpendingFromTheSameMerchant() {
        // Same merchant (e.g. a grocery store visited whenever), but no regular interval.
        List<Transaction> txns = List.of(
                expense("bigbasket", LocalDate.of(2026, 5, 2), BigDecimal.valueOf(1500)),
                expense("bigbasket", LocalDate.of(2026, 5, 4), BigDecimal.valueOf(800)),
                expense("bigbasket", LocalDate.of(2026, 6, 20), BigDecimal.valueOf(2200))
        );
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        var results = recurringService.detectForUser(userId);

        assertThat(results).isEmpty();
    }

    @Test
    void doesNotFlagRegularIntervalWithInconsistentAmounts() {
        List<Transaction> txns = List.of(
                expense("random-shop", LocalDate.of(2026, 5, 5), BigDecimal.valueOf(500)),
                expense("random-shop", LocalDate.of(2026, 6, 5), BigDecimal.valueOf(5000)), // wildly different
                expense("random-shop", LocalDate.of(2026, 7, 5), BigDecimal.valueOf(300))
        );
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        var results = recurringService.detectForUser(userId);

        assertThat(results).isEmpty();
    }

    @Test
    void resetsStaleRecurringFlag_whenPatternNoLongerHolds() {
        Transaction t = expense("old-subscription", LocalDate.of(2026, 1, 1), BigDecimal.valueOf(299));
        t.setRecurring(true); // simulate a stale flag from a previous run
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(t));

        recurringService.detectForUser(userId);

        assertThat(t.isRecurring()).isFalse();
    }

    // --- MARK_SUBSCRIPTION rule-driven recurring flag ---

    private CategoryRule markSubscriptionRule() {
        CategoryRule r = new CategoryRule();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setScope(CategoryRule.Scope.GLOBAL);
        r.setActionType(CategoryRule.ActionType.MARK_SUBSCRIPTION);
        return r;
    }

    @Test
    void flagsATransactionAsRecurring_onASingleOccurrence_whenAMarkSubscriptionRuleMatches() {
        // The whole point of a MARK_SUBSCRIPTION rule: doesn't need to wait for 2+ occurrences
        // with a regular gap the way organic pattern detection (tested above) does.
        Transaction t = expense("NETFLIX.COM", LocalDate.of(2026, 7, 1), BigDecimal.valueOf(649));
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(t));
        // Bug fix: expense() (the shared fixture above) never sets a description, only a merchant
        // -- so t.getDescription() is null here. anyString() looks like it should match anything,
        // but Mockito's anyString() explicitly excludes null (a well-known gotcha; any() is the
        // one that matches null too). The stub silently never matched, the mock fell back to its
        // unstubbed empty-list default, and the "did a MARK_SUBSCRIPTION rule match" check always
        // saw zero matches regardless of what was configured above -- the test was asserting on
        // behavior it wasn't actually exercising.
        when(ruleEngineService.ruleSet(userId)).thenReturn(List.of(markSubscriptionRule()));
        // The service now hoists the two rule queries out of the per-transaction loop and
        // evaluates against the pre-fetched set, so this stubs the List<CategoryRule> overload.
        when(ruleEngineService.evaluateSideEffectRules(anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(new RuleEngineService.RuleMatch(markSubscriptionRule())));

        recurringService.detectForUser(userId);

        assertThat(t.isRecurring()).isTrue();
    }

    @Test
    void doesNotOverrideAnAlreadyPatternDetectedRecurringTransaction_withARedundantRuleCheck() {
        // Pattern detection already flagged this group true before the rule-check loop runs --
        // the rule stub below is irrelevant here since evaluateSideEffectRules should never even
        // be consulted for a transaction already recurring=true (see the `if (t.isRecurring())
        // continue;` guard).
        List<Transaction> txns = List.of(
                expense("spotify", LocalDate.of(2026, 5, 1), BigDecimal.valueOf(119)),
                expense("spotify", LocalDate.of(2026, 6, 1), BigDecimal.valueOf(119)),
                expense("spotify", LocalDate.of(2026, 7, 1), BigDecimal.valueOf(119))
        );
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        recurringService.detectForUser(userId);

        assertThat(txns).allSatisfy(t -> assertThat(t.isRecurring()).isTrue());
        verify(ruleEngineService, never()).evaluateSideEffectRules(anyList(), any(), any(), any(), any());
    }

    @Test
    void doesNotFlagRecurring_whenNoPatternAndNoRuleMatch() {
        Transaction t = expense("one-off-purchase", LocalDate.of(2026, 7, 1), BigDecimal.valueOf(2000));
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(t));
        // ruleEngineService.evaluateSideEffectRules unstubbed -- defaults to empty list.

        recurringService.detectForUser(userId);

        assertThat(t.isRecurring()).isFalse();
    }

    // --- RECURRING_DETECTION_RUN audit summary (Financial Intelligence Workspace,
    // Reconciliation Monitor module -- see detectForUser's own doc comment on the counters) ---

    @Test
    void detectForUser_recordsASummaryAuditEntry() {
        List<Transaction> txns = List.of(
                expense("netflix", LocalDate.of(2026, 5, 1), BigDecimal.valueOf(499)),
                expense("netflix", LocalDate.of(2026, 6, 1), BigDecimal.valueOf(499)),
                expense("netflix", LocalDate.of(2026, 7, 1), BigDecimal.valueOf(499))
        );
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        recurringService.detectForUser(userId);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> metadataCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).record(eq(userId), eq("RECURRING_DETECTION_RUN"), eq("Transaction"),
                org.mockito.ArgumentMatchers.isNull(), metadataCaptor.capture());

        assertThat(metadataCaptor.getValue())
                .containsEntry("transactionsProcessed", 3)
                .containsEntry("recurringGroupsFound", 1)
                .containsEntry("recurringTransactionsFlagged", 3L);
    }

    /**
     * Bug 10 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). This used to assert the OPPOSITE --
     * that an audit row is written even when nothing is recurring -- which was exactly the "one
     * audit row per page view" the bug report flagged, since Dashboard.tsx/Insights.tsx call this
     * on ordinary page load via a plain GET. A transaction that was never recurring and still
     * isn't after this run is not a change; see RecurringService's own class doc comment.
     */
    @Test
    void detectForUser_writesNoAuditRecord_whenNothingChanged() {
        Transaction t = expense("one-off-purchase", LocalDate.of(2026, 7, 1), BigDecimal.valueOf(2000));
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(t));

        var results = recurringService.detectForUser(userId);

        assertThat(results).isEmpty();
        verify(transactionRepository, never()).saveAll(any());
        verifyNoInteractions(auditService);
    }

    /** The other half of Bug 10's fix: a transaction that WAS recurring and genuinely stops (the
     *  reset-to-false path) is a real change and must still be written and audited -- the fix
     *  only skips writes for transactions whose flag doesn't move, not every "nothing found"
     *  outcome. */
    @Test
    void detectForUser_writesAndAudits_whenAPreviouslyRecurringTransactionStopsBeingOne() {
        Transaction t = expense("cancelled-subscription", LocalDate.of(2026, 7, 1), BigDecimal.valueOf(499));
        t.setRecurring(true);
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(t));

        var results = recurringService.detectForUser(userId);

        assertThat(results).isEmpty();
        assertThat(t.isRecurring()).isFalse();
        verify(transactionRepository).saveAll(List.of(t));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> metadataCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).record(eq(userId), eq("RECURRING_DETECTION_RUN"), eq("Transaction"),
                org.mockito.ArgumentMatchers.isNull(), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("transactionsProcessed", 1)
                .containsEntry("recurringGroupsFound", 0)
                .containsEntry("recurringTransactionsFlagged", 0L);
    }

    /** A second, otherwise-untouched transaction must not be re-saved or re-audited alongside a
     *  genuinely changed one -- proving `changed` is a real filter, not saveAll(active) with
     *  extra steps. */
    @Test
    void detectForUser_savesOnlyTheChangedTransaction_notEveryActiveOne() {
        Transaction changing = expense("cancelled-subscription", LocalDate.of(2026, 7, 1), BigDecimal.valueOf(499));
        changing.setRecurring(true);
        Transaction unrelated = expense("one-off-purchase", LocalDate.of(2026, 7, 2), BigDecimal.valueOf(2000));
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(changing, unrelated));

        recurringService.detectForUser(userId);

        verify(transactionRepository).saveAll(List.of(changing));
    }

    // --- Admin Portal Phase 8: RECURRING_DETECTION_ENABLED feature flag gate ---

    @Test
    void detectForUser_isANoOp_whenTheFeatureFlagIsDisabled() {
        when(featureFlagService.isEnabled("RECURRING_DETECTION_ENABLED")).thenReturn(false);
        Transaction t = expense("netflix", LocalDate.of(2026, 7, 1), BigDecimal.valueOf(649));
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(t));

        var results = recurringService.detectForUser(userId);

        assertThat(results).isEmpty();
        verify(transactionRepository, never()).saveAll(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void detectForUser_doesNotClearAnExistingRecurringFlag_whenTheFeatureFlagIsDisabled() {
        // Disabling the flag pauses new detection -- it must not silently wipe a badge a prior,
        // enabled run already set. See detectForUser's own doc comment on why this distinction
        // matters for an admin flipping the flag off mid-incident.
        when(featureFlagService.isEnabled("RECURRING_DETECTION_ENABLED")).thenReturn(false);
        Transaction t = expense("netflix", LocalDate.of(2026, 7, 1), BigDecimal.valueOf(649));
        t.setRecurring(true);
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(t));

        recurringService.detectForUser(userId);

        assertThat(t.isRecurring()).isTrue();
    }

    /**
     * BH-026. Two points cannot evidence an interval, and the old threshold of 2 made the
     * regularity check structurally incapable of rejecting anything.
     *
     * <p>With two transactions {@code gaps} holds one element, {@code avgGap} equals it, and
     * {@code Math.abs(g - avgGap)} is zero -- so {@code gapRegular} was true for ANY spacing. The
     * only filters left were the amount tolerance and the 5-95 day window, which two ordinary
     * purchases from the same merchant clear routinely.
     */
    @Test
    void doesNotCallTwoChargesAPattern() {
        // Two coffees three weeks apart: similar amount, inside the day window, nothing recurring
        // about them. This used to be reported as a Monthly subscription with a predicted next
        // charge date.
        List<Transaction> txns = List.of(
                expense("blue tokai", LocalDate.of(2026, 6, 2), BigDecimal.valueOf(420)),
                expense("blue tokai", LocalDate.of(2026, 6, 23), BigDecimal.valueOf(430))
        );
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        var results = recurringService.detectForUser(userId);

        assertThat(results).isEmpty();
        assertThat(txns).noneMatch(Transaction::isRecurring);
    }

    /**
     * The other half of the same threshold: a third charge at the same spacing IS the evidence,
     * and must still be detected. Raising the minimum must narrow false positives without
     * disabling the feature.
     */
    @Test
    void aThirdChargeAtTheSameSpacingIsAPattern() {
        List<Transaction> txns = List.of(
                expense("blue tokai", LocalDate.of(2026, 6, 2), BigDecimal.valueOf(420)),
                expense("blue tokai", LocalDate.of(2026, 6, 23), BigDecimal.valueOf(430)),
                expense("blue tokai", LocalDate.of(2026, 7, 14), BigDecimal.valueOf(425))
        );
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        var results = recurringService.detectForUser(userId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).occurrences()).isEqualTo(3);
        assertThat(txns).allMatch(Transaction::isRecurring);
    }

    /**
     * And the case that proves the check now actually checks: three charges whose spacing does NOT
     * agree. Under the old threshold this reached the regularity test with two gaps and could
     * fail it -- but nothing asserted that it did, so the test suite never distinguished "the
     * check works" from "the check cannot fail".
     */
    @Test
    void threeChargesWithIrregularSpacingAreNotAPattern() {
        List<Transaction> txns = List.of(
                expense("blue tokai", LocalDate.of(2026, 6, 2), BigDecimal.valueOf(420)),
                expense("blue tokai", LocalDate.of(2026, 6, 9), BigDecimal.valueOf(425)),
                expense("blue tokai", LocalDate.of(2026, 7, 28), BigDecimal.valueOf(430))
        );
        when(transactionRepository.findByUserId(userId)).thenReturn(txns);

        var results = recurringService.detectForUser(userId);

        assertThat(results).isEmpty();
        assertThat(txns).noneMatch(Transaction::isRecurring);
    }
}
