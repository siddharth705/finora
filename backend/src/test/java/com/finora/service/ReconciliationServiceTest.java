package com.finora.service;

import com.finora.entity.Transaction;
import com.finora.entity.TransactionRelationship;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReconciliationServiceTest {

    private TransactionRepository transactionRepository;
    private RelationshipService relationshipService;
    private AuditService auditService;
    private TransactionGraphService transactionGraphService;
    private ReconciliationService reconciliationService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        // Unstubbed -- Mockito's default boolean/List return is false/empty, so every existing
        // test here (none of which are about relationship-assisted matching -- see the dedicated
        // relationship tests below) preserves the original plain amount+date+"payment"-heuristic
        // behavior unchanged.
        relationshipService = mock(RelationshipService.class);
        auditService = mock(AuditService.class);
        transactionGraphService = mock(TransactionGraphService.class);
        reconciliationService = new ReconciliationService(transactionRepository, relationshipService, auditService,
                transactionGraphService);
    }

    private Transaction txn(UUID id, UUID accountId, LocalDate date, BigDecimal amount,
                            Transaction.Type type, String description, Instant createdAt) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "createdAt", createdAt);
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnDate(date);
        t.setAmount(amount);
        t.setTxnType(type);
        t.setDescription(description);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        return t;
    }

    // --- Duplicates (in-memory grouping, replacing the old per-transaction
    // findPotentialDuplicates() query -- see ReconciliationService's class comment) ---

    @Test
    void reconcileForUser_flagsNewerIdenticalTransactionAsDuplicate() {
        UUID accountId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 10);

        Transaction original = txn(UUID.randomUUID(), accountId, date, new BigDecimal("486.00"),
                Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR", Instant.parse("2026-07-10T10:00:00Z"));
        Transaction duplicate = txn(UUID.randomUUID(), accountId, date, new BigDecimal("486.00"),
                Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR", Instant.parse("2026-07-10T10:05:00Z"));

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(original, duplicate));

        reconciliationService.reconcileForUser(userId);

        assertThat(original.getIsDuplicateOf()).isNull();
        assertThat(duplicate.getIsDuplicateOf()).isEqualTo(original.getId());
        assertThat(duplicate.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.DUPLICATE);
    }

    /**
     * Phase 1, docs/proposals/reconciliation-evolution-roadmap-proposal.md. Previously canonical
     * selection was purely "whichever was created first" -- a bank statement imported AFTER a
     * Gmail receipt that happens to collide on the exact duplicate key would lose to it. Source
     * trust (CSV_IMPORT=95 > GMAIL_IMPORT=60) now wins outright, regardless of creation order.
     */
    @Test
    void reconcileForUser_prefersTheHigherTrustSource_asCanonical_evenWhenCreatedLater() {
        UUID accountId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 10);

        Transaction gmailReceipt = txn(UUID.randomUUID(), accountId, date, new BigDecimal("499.00"),
                Transaction.Type.EXPENSE, "AMAZON ORDER 4471", Instant.parse("2026-07-10T09:00:00Z"));
        gmailReceipt.setSource(Transaction.Source.GMAIL_IMPORT);
        Transaction bankStatement = txn(UUID.randomUUID(), accountId, date, new BigDecimal("499.00"),
                Transaction.Type.EXPENSE, "AMAZON ORDER 4471", Instant.parse("2026-07-10T18:00:00Z"));
        bankStatement.setSource(Transaction.Source.CSV_IMPORT);

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(gmailReceipt, bankStatement));

        reconciliationService.reconcileForUser(userId);

        assertThat(bankStatement.getIsDuplicateOf())
                .as("the bank statement is the higher-trust source; it stays canonical despite arriving later")
                .isNull();
        assertThat(gmailReceipt.getIsDuplicateOf()).isEqualTo(bankStatement.getId());
    }

    /** Same source, same trust -- the tiebreak degrades back to creation order, unchanged. */
    @Test
    void reconcileForUser_fallsBackToCreationOrder_whenBothRowsShareASource() {
        UUID accountId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 10);

        Transaction earlier = txn(UUID.randomUUID(), accountId, date, new BigDecimal("499.00"),
                Transaction.Type.EXPENSE, "AMAZON ORDER 4471", Instant.parse("2026-07-10T09:00:00Z"));
        earlier.setSource(Transaction.Source.CSV_IMPORT);
        Transaction later = txn(UUID.randomUUID(), accountId, date, new BigDecimal("499.00"),
                Transaction.Type.EXPENSE, "AMAZON ORDER 4471", Instant.parse("2026-07-10T18:00:00Z"));
        later.setSource(Transaction.Source.CSV_IMPORT);

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(earlier, later));

        reconciliationService.reconcileForUser(userId);

        assertThat(earlier.getIsDuplicateOf()).isNull();
        assertThat(later.getIsDuplicateOf()).isEqualTo(earlier.getId());
    }

    @Test
    void reconcileForUser_doesNotFlagDistinctTransactionsAsDuplicates() {
        UUID accountId = UUID.randomUUID();
        Transaction a = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 10), new BigDecimal("486.00"),
                Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR", Instant.parse("2026-07-10T10:00:00Z"));
        Transaction b = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 11), new BigDecimal("612.00"),
                Transaction.Type.EXPENSE, "ZOMATO ORDER 771", Instant.parse("2026-07-11T10:00:00Z"));

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(a, b));

        reconciliationService.reconcileForUser(userId);

        assertThat(a.getIsDuplicateOf()).isNull();
        assertThat(b.getIsDuplicateOf()).isNull();
    }

    @Test
    void reconcileForUser_matchesDuplicatesRegardlessOfAmountScale() {
        // stripTrailingZeros().toPlainString() normalization -- "486.00" and "486.0" must be
        // treated as the same numeric amount for grouping, exactly like the original query's
        // SQL numeric equality did (100 = 100.00 is true in SQL, unlike naive string equality).
        UUID accountId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 10);

        Transaction original = txn(UUID.randomUUID(), accountId, date, new BigDecimal("486.00"),
                Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR", Instant.parse("2026-07-10T10:00:00Z"));
        Transaction duplicate = txn(UUID.randomUUID(), accountId, date, new BigDecimal("486.0"),
                Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR", Instant.parse("2026-07-10T10:05:00Z"));

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(original, duplicate));

        reconciliationService.reconcileForUser(userId);

        assertThat(duplicate.getIsDuplicateOf()).isEqualTo(original.getId());
    }

    @Test
    void reconcileForUser_neverGroupsTwoNullDescriptionTransactionsAsDuplicatesOfEachOther() {
        // Faithful to the original query's SQL semantics: `t.description = :description` with a
        // null bind parameter is never true, not even against another null row. Two
        // no-description transactions must stay ungrouped, matching that.
        UUID accountId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 10);

        Transaction a = txn(UUID.randomUUID(), accountId, date, new BigDecimal("100.00"),
                Transaction.Type.EXPENSE, null, Instant.parse("2026-07-10T10:00:00Z"));
        Transaction b = txn(UUID.randomUUID(), accountId, date, new BigDecimal("100.00"),
                Transaction.Type.EXPENSE, null, Instant.parse("2026-07-10T10:05:00Z"));

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(a, b));

        reconciliationService.reconcileForUser(userId);

        assertThat(a.getIsDuplicateOf()).isNull();
        assertThat(b.getIsDuplicateOf()).isNull();
    }

    // --- Transfers ---

    @Test
    void reconcileForUser_matchesCreditCardPaymentAsInternalTransfer() {
        UUID savingsAccount = UUID.randomUUID();
        UUID cardAccount = UUID.randomUUID();

        Transaction debitFromSavings = txn(UUID.randomUUID(), savingsAccount, LocalDate.of(2026, 7, 10),
                new BigDecimal("18500.00"), Transaction.Type.EXPENSE, "NEFT Payment to Card", Instant.now());
        Transaction creditOnCard = txn(UUID.randomUUID(), cardAccount, LocalDate.of(2026, 7, 11),
                new BigDecimal("18500.00"), Transaction.Type.INCOME, "Payment Received - Thank You", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(debitFromSavings, creditOnCard));

        reconciliationService.reconcileForUser(userId);

        assertThat(debitFromSavings.isTransfer()).isTrue();
        assertThat(creditOnCard.isTransfer()).isTrue();
        assertThat(debitFromSavings.getTransferPairId()).isEqualTo(creditOnCard.getId());
        assertThat(creditOnCard.getTransferPairId()).isEqualTo(debitFromSavings.getId());
        assertThat(debitFromSavings.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.TRANSFER);
    }

    @Test
    void reconcileForUser_doesNotMatchTransferAcrossMoreThanFourDays() {
        UUID savingsAccount = UUID.randomUUID();
        UUID cardAccount = UUID.randomUUID();

        Transaction debit = txn(UUID.randomUUID(), savingsAccount, LocalDate.of(2026, 7, 1),
                new BigDecimal("18500.00"), Transaction.Type.EXPENSE, "Payment to card", Instant.now());
        Transaction credit = txn(UUID.randomUUID(), cardAccount, LocalDate.of(2026, 7, 20),
                new BigDecimal("18500.00"), Transaction.Type.INCOME, "Payment received", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(debit, credit));

        reconciliationService.reconcileForUser(userId);

        assertThat(debit.isTransfer()).isFalse();
        assertThat(credit.isTransfer()).isFalse();
    }

    @Test
    void reconcileForUser_doesNotMatchTransferOnSameAccount() {
        UUID accountId = UUID.randomUUID();
        Transaction debit = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 10),
                new BigDecimal("500.00"), Transaction.Type.EXPENSE, "Payment sent", Instant.now());
        Transaction credit = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 11),
                new BigDecimal("500.00"), Transaction.Type.INCOME, "Payment received", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(debit, credit));

        reconciliationService.reconcileForUser(userId);

        // Same account on both legs isn't a transfer between accounts — it's just two transactions.
        assertThat(debit.isTransfer()).isFalse();
        assertThat(credit.isTransfer()).isFalse();
    }

    @Test
    void reconcileForUser_doesNotTreatASalaryCreditAsATransfer_evenWhenDescriptionSaysPayment() {
        // Real-world pattern: "NEFT SALARY PAYMENT XYZ CORP" contains the word "payment" and
        // could otherwise false-positive-match the transfer heuristic against an unrelated
        // same-amount expense. Salary is external income, never an internal transfer.
        UUID salaryAccount = UUID.randomUUID();
        UUID otherAccount = UUID.randomUUID();

        Transaction salaryCredit = txn(UUID.randomUUID(), salaryAccount, LocalDate.of(2026, 7, 1),
                new BigDecimal("75000.00"), Transaction.Type.INCOME, "NEFT SALARY PAYMENT XYZ CORP", Instant.now());
        Transaction unrelatedExpense = txn(UUID.randomUUID(), otherAccount, LocalDate.of(2026, 7, 2),
                new BigDecimal("75000.00"), Transaction.Type.EXPENSE, "Payment to landlord", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(salaryCredit, unrelatedExpense));

        reconciliationService.reconcileForUser(userId);

        assertThat(salaryCredit.isTransfer()).isFalse();
        assertThat(unrelatedExpense.isTransfer()).isFalse();
        assertThat(salaryCredit.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    // --- Relationship-assisted transfer matching (docs/rule-engine-relationship-engine-eds.md
    // §4: a known OWN_ACCOUNT relationship identifier widens the matching window rather than
    // replacing the existing amount+date+"payment"-wording heuristic) ---

    @Test
    void reconcileForUser_widensMatchWindowPastFourDays_whenAnOwnAccountRelationshipMatches() {
        UUID savingsAccount = UUID.randomUUID();
        UUID cardAccount = UUID.randomUUID();

        Transaction debit = txn(UUID.randomUUID(), savingsAccount, LocalDate.of(2026, 7, 1),
                new BigDecimal("18500.00"), Transaction.Type.EXPENSE, "Payment to XX4802", Instant.now());
        Transaction credit = txn(UUID.randomUUID(), cardAccount, LocalDate.of(2026, 7, 8), // 7 days apart -- beyond the default 4-day window
                new BigDecimal("18500.00"), Transaction.Type.INCOME, "Payment received", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(debit, credit));
        when(relationshipService.ownAccountIdentifierValues(userId)).thenReturn(List.of("4802"));

        reconciliationService.reconcileForUser(userId);

        assertThat(debit.isTransfer()).isTrue();
        assertThat(credit.isTransfer()).isTrue();
    }

    @Test
    void reconcileForUser_stillRespectsTenDayOuterWindow_evenWithARelationshipMatch() {
        UUID savingsAccount = UUID.randomUUID();
        UUID cardAccount = UUID.randomUUID();

        Transaction debit = txn(UUID.randomUUID(), savingsAccount, LocalDate.of(2026, 7, 1),
                new BigDecimal("18500.00"), Transaction.Type.EXPENSE, "Payment to XX4802", Instant.now());
        Transaction credit = txn(UUID.randomUUID(), cardAccount, LocalDate.of(2026, 7, 20), // 19 days apart -- beyond even the widened window
                new BigDecimal("18500.00"), Transaction.Type.INCOME, "Payment received", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(debit, credit));
        when(relationshipService.ownAccountIdentifierValues(userId)).thenReturn(List.of("4802"));

        reconciliationService.reconcileForUser(userId);

        assertThat(debit.isTransfer()).isFalse();
        assertThat(credit.isTransfer()).isFalse();
    }

    @Test
    void reconcileForUser_relationshipMatchAloneCanTriggerPairing_withoutPaymentWordingInDescription() {
        // Neither description contains "payment" -- previously this pair would never even be
        // considered (looksLikeTransfer gated on that keyword). A relationship identifier match
        // is independent evidence and can trigger evaluation on its own.
        UUID savingsAccount = UUID.randomUUID();
        UUID otherAccount = UUID.randomUUID();

        Transaction debit = txn(UUID.randomUUID(), savingsAccount, LocalDate.of(2026, 7, 10),
                new BigDecimal("5000.00"), Transaction.Type.EXPENSE, "UPI TO SELF XX4802", Instant.now());
        Transaction credit = txn(UUID.randomUUID(), otherAccount, LocalDate.of(2026, 7, 11),
                new BigDecimal("5000.00"), Transaction.Type.INCOME, "UPI CREDIT RECEIVED", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(debit, credit));
        when(relationshipService.ownAccountIdentifierValues(userId)).thenReturn(List.of("4802"));

        reconciliationService.reconcileForUser(userId);

        assertThat(debit.isTransfer()).isTrue();
        assertThat(credit.isTransfer()).isTrue();
    }

    // --- Refunds ---

    @Test
    void reconcileForUser_linksARefundKeywordCreditBackToTheOriginalSameAccountExpense() {
        UUID accountId = UUID.randomUUID();

        Transaction purchase = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 1),
                new BigDecimal("2499.00"), Transaction.Type.EXPENSE, "AMAZON ORDER 4471", Instant.now());
        Transaction refund = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 9),
                new BigDecimal("2499.00"), Transaction.Type.INCOME, "AMAZON REFUND 4471", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(purchase, refund));

        reconciliationService.reconcileForUser(userId);

        assertThat(refund.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
        assertThat(refund.getRefundOfTransactionId()).isEqualTo(purchase.getId());
    }

    @Test
    void reconcileForUser_linksAReversalKeywordCreditAsReversal_notRefund() {
        UUID accountId = UUID.randomUUID();

        Transaction purchase = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 1),
                new BigDecimal("1200.00"), Transaction.Type.EXPENSE, "NEFT PAYMENT TO XYZ", Instant.now());
        Transaction reversal = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 2),
                new BigDecimal("1200.00"), Transaction.Type.INCOME, "PAYMENT REVERSAL NEFT XYZ", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(purchase, reversal));

        reconciliationService.reconcileForUser(userId);

        assertThat(reversal.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REVERSAL);
        assertThat(reversal.getRefundOfTransactionId()).isEqualTo(purchase.getId());
    }

    @Test
    void reconcileForUser_stillClassifiesAPlainRefundKeywordAsRefund_notReversal() {
        UUID accountId = UUID.randomUUID();

        Transaction purchase = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 1),
                new BigDecimal("899.00"), Transaction.Type.EXPENSE, "MYNTRA ORDER 552", Instant.now());
        Transaction refund = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 5),
                new BigDecimal("899.00"), Transaction.Type.INCOME, "MYNTRA RETURN REFUND 552", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(purchase, refund));

        reconciliationService.reconcileForUser(userId);

        assertThat(refund.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
    }

    @Test
    void reconcileForUser_linksAPartialRefundByMerchantMatch_withoutRefundKeyword() {
        UUID accountId = UUID.randomUUID();

        Transaction purchase = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 1),
                new BigDecimal("3000.00"), Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR", Instant.now());
        purchase.setMerchant("swiggy ordr");
        Transaction partialCredit = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 3),
                new BigDecimal("500.00"), Transaction.Type.INCOME, "SWIGGY CREDIT ADJ", Instant.now());
        partialCredit.setMerchant("swiggy ordr");

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(purchase, partialCredit));

        reconciliationService.reconcileForUser(userId);

        assertThat(partialCredit.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
        assertThat(partialCredit.getRefundOfTransactionId()).isEqualTo(purchase.getId());
    }

    @Test
    void reconcileForUser_doesNotLinkARefundAcrossDifferentAccounts() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        Transaction purchase = txn(UUID.randomUUID(), accountA, LocalDate.of(2026, 7, 1),
                new BigDecimal("2499.00"), Transaction.Type.EXPENSE, "AMAZON ORDER 4471", Instant.now());
        Transaction refund = txn(UUID.randomUUID(), accountB, LocalDate.of(2026, 7, 9),
                new BigDecimal("2499.00"), Transaction.Type.INCOME, "AMAZON REFUND 4471", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(purchase, refund));

        reconciliationService.reconcileForUser(userId);

        assertThat(refund.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(refund.getRefundOfTransactionId()).isNull();
    }

    @Test
    void reconcileForUser_doesNotLinkARefundExceedingTheOriginalPurchaseAmount() {
        UUID accountId = UUID.randomUUID();

        Transaction purchase = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 1),
                new BigDecimal("1000.00"), Transaction.Type.EXPENSE, "AMAZON ORDER 55", Instant.now());
        Transaction credit = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 5),
                new BigDecimal("1500.00"), Transaction.Type.INCOME, "AMAZON REFUND 55", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(purchase, credit));

        reconciliationService.reconcileForUser(userId);

        assertThat(credit.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(credit.getRefundOfTransactionId()).isNull();
    }

    @Test
    void reconcileForUser_doesNotLinkARefundOutsideTheWindow() {
        UUID accountId = UUID.randomUUID();

        Transaction purchase = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 1, 1),
                new BigDecimal("2499.00"), Transaction.Type.EXPENSE, "AMAZON ORDER 4471", Instant.now());
        Transaction refund = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 20), // > 180 days later
                new BigDecimal("2499.00"), Transaction.Type.INCOME, "AMAZON REFUND 4471", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(purchase, refund));

        reconciliationService.reconcileForUser(userId);

        assertThat(refund.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(refund.getRefundOfTransactionId()).isNull();
    }

    @Test
    void reconcileForUser_prefersExactAmountMatchOverMerchantOnlyMatch_whenBothAreCandidates() {
        UUID accountId = UUID.randomUUID();

        Transaction earlierPartialMatch = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 6, 1),
                new BigDecimal("5000.00"), Transaction.Type.EXPENSE, "AMAZON ORDER 1", Instant.now());
        earlierPartialMatch.setMerchant("amazon order");
        Transaction exactMatch = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 1),
                new BigDecimal("999.00"), Transaction.Type.EXPENSE, "AMAZON ORDER 2", Instant.now());
        exactMatch.setMerchant("amazon order");
        Transaction refund = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 5),
                new BigDecimal("999.00"), Transaction.Type.INCOME, "AMAZON REFUND", Instant.now());
        refund.setMerchant("amazon order");

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(earlierPartialMatch, exactMatch, refund));

        reconciliationService.reconcileForUser(userId);

        assertThat(refund.getRefundOfTransactionId()).isEqualTo(exactMatch.getId());
    }

    @Test
    void reconcileForUser_doesNotLinkARefundWithNoKeywordAndNoMerchantMatch() {
        UUID accountId = UUID.randomUUID();

        Transaction purchase = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 1),
                new BigDecimal("2499.00"), Transaction.Type.EXPENSE, "AMAZON ORDER 4471", Instant.now());
        Transaction unrelatedIncome = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 5),
                new BigDecimal("2499.00"), Transaction.Type.INCOME, "FREELANCE PAYOUT", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(purchase, unrelatedIncome));

        reconciliationService.reconcileForUser(userId);

        assertThat(unrelatedIncome.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(unrelatedIncome.getRefundOfTransactionId()).isNull();
    }

    // BH-007. Reproduces the finding exactly: one EXPENSE, two INCOME rows at the same merchant
    // within the window, each individually no larger than the expense. Before the fix, both passed
    // the per-pair "not more than the expense's amount" guard independently and both got marked
    // REFUND -- 2x the expense's amount silently excluded from every total.
    @Test
    void reconcileForUser_capsCumulativeRefundsAtTheExpenseAmount_acrossMultipleMatchingIncomeRows() {
        UUID accountId = UUID.randomUUID();

        Transaction purchase = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 1),
                new BigDecimal("500.00"), Transaction.Type.EXPENSE, "ACME STORE", Instant.now());
        purchase.setMerchant("acme store");
        // The refund -- earlier date, so it is processed first and claims the expense's capacity.
        Transaction refund = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 3),
                new BigDecimal("500.00"), Transaction.Type.INCOME, "ACME REFUND", Instant.now());
        refund.setMerchant("acme store");
        // An unrelated payout that happens to match the same merchant token -- exactly the
        // coincidence the original finding named, not a contrived case.
        Transaction unrelatedPayout = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 5),
                new BigDecimal("500.00"), Transaction.Type.INCOME, "ACME PAYOUT", Instant.now());
        unrelatedPayout.setMerchant("acme store");

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(purchase, refund, unrelatedPayout));

        reconciliationService.reconcileForUser(userId);

        assertThat(refund.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
        assertThat(refund.getRefundOfTransactionId()).isEqualTo(purchase.getId());
        // The expense's ₹500 capacity is gone -- the second income row is real, unrelated income
        // and must stay OK, not be silently excluded from every total.
        assertThat(unrelatedPayout.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(unrelatedPayout.getRefundOfTransactionId()).isNull();
    }

    // BH-007, the other half: capacity already claimed by a PRIOR run (a REFUND row already sitting
    // in the user's history, not matched fresh in this pass) must count too, or re-running
    // reconciliation over full history would let a new income row double-dip against an expense
    // that is already fully accounted for. Provably in scope for reconcileForImport's own windowed
    // fetch as well -- see the fix's comment on why CANDIDATE_WINDOW_DAYS guarantees this.
    @Test
    void reconcileForUser_treatsAnAlreadyResolvedRefundAsConsumingCapacity_notJustMatchesMadeThisRun() {
        UUID accountId = UUID.randomUUID();

        Transaction purchase = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 1),
                new BigDecimal("500.00"), Transaction.Type.EXPENSE, "ACME STORE", Instant.now());
        purchase.setMerchant("acme store");

        Transaction alreadyRefunded = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 3),
                new BigDecimal("500.00"), Transaction.Type.INCOME, "ACME REFUND", Instant.now());
        alreadyRefunded.setMerchant("acme store");
        // Simulates a match a PRIOR reconciliation run already made and persisted.
        alreadyRefunded.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        alreadyRefunded.setRefundOfTransactionId(purchase.getId());

        Transaction newIncome = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 5),
                new BigDecimal("500.00"), Transaction.Type.INCOME, "ACME PAYOUT", Instant.now());
        newIncome.setMerchant("acme store");

        when(transactionRepository.findByUserId(userId))
                .thenReturn(List.of(purchase, alreadyRefunded, newIncome));

        reconciliationService.reconcileForUser(userId);

        assertThat(newIncome.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(newIncome.getRefundOfTransactionId()).isNull();
    }

    // --- RECONCILIATION_RUN audit summary (Financial Intelligence Workspace, Reconciliation
    // Monitor module -- see ReconciliationService.reconcileForUser's own doc comment on the
    // counters) ---

    @Test
    void reconcileForUser_recordsASummaryAuditEntry_withCountsFromThisRunOnly() {
        UUID accountId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 10);

        Transaction original = txn(UUID.randomUUID(), accountId, date, new BigDecimal("486.00"),
                Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR", Instant.parse("2026-07-10T10:00:00Z"));
        Transaction duplicate = txn(UUID.randomUUID(), accountId, date, new BigDecimal("486.00"),
                Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR", Instant.parse("2026-07-10T10:05:00Z"));

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(original, duplicate));

        reconciliationService.reconcileForUser(userId);

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> metadataCaptor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        org.mockito.Mockito.verify(auditService).record(
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.eq("RECONCILIATION_RUN"),
                org.mockito.ArgumentMatchers.eq("Transaction"), org.mockito.ArgumentMatchers.isNull(),
                metadataCaptor.capture());

        assertThat(metadataCaptor.getValue())
                .containsEntry("transactionsProcessed", 2)
                .containsEntry("duplicatesFound", 1)
                .containsEntry("transfersMatched", 0)
                .containsEntry("refundsMatched", 0);
    }

    @Test
    void reconcileForUser_countsOneTransferMatchPerPair_notPerRow() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        Transaction payment = txn(UUID.randomUUID(), accountA, LocalDate.of(2026, 7, 10), new BigDecimal("5000.00"),
                Transaction.Type.EXPENSE, "PAYMENT TO SAVINGS", Instant.now());
        Transaction credit = txn(UUID.randomUUID(), accountB, LocalDate.of(2026, 7, 11), new BigDecimal("5000.00"),
                Transaction.Type.INCOME, "CREDIT FROM CHECKING", Instant.now());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(payment, credit));

        reconciliationService.reconcileForUser(userId);

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> metadataCaptor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        org.mockito.Mockito.verify(auditService).record(
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.eq("RECONCILIATION_RUN"),
                org.mockito.ArgumentMatchers.eq("Transaction"), org.mockito.ArgumentMatchers.isNull(),
                metadataCaptor.capture());

        // Two transactions became transfers, but that's ONE match event, not two.
        assertThat(metadataCaptor.getValue()).containsEntry("transfersMatched", 1);
    }

    /**
     * BH-044. This test used to be {@code recordsTheSummary_evenWhenNothingMatched} and asserted
     * the opposite, backing a decision the service stated in its own comment: "'ran and found
     * nothing new' is itself the answer to 'when did this last run'".
     *
     * <p>Inverted deliberately, not overlooked. Reconciliation is synchronous and unconditional
     * after every transaction create, update and delete, every import confirm and every statement
     * delete — so an all-zero run is written at the same instant as the {@code TRANSACTION_*} row
     * that triggered it and carries no fact that row does not. The trigger answers "when did this
     * last run". What the zeros cost is not nothing: {@code audit_logs} has no retention, no
     * partitioning and no archival, and this doubled its growth against ordinary ledger editing.
     *
     * <p>The two cases the original reasoning was actually protecting both keep their row, and have
     * their own tests below: a run that reclassified something, and a run that was slow.
     */
    @Test
    void reconcileForUser_writesNoAuditRow_whenTheRunReclassifiedNothing() {
        Transaction lone = txn(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 7, 10),
                new BigDecimal("100.00"), Transaction.Type.EXPENSE, "ONE-OFF PURCHASE", Instant.now());
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(lone));

        reconciliationService.reconcileForUser(userId);

        org.mockito.Mockito.verify(auditService, org.mockito.Mockito.never()).record(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("RECONCILIATION_RUN"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * The half that must never be dropped, asserted separately from the counter tests above so it
     * cannot be lost if those change shape. A run that reclassified a transaction is the audit
     * trail's whole subject.
     */
    @Test
    void reconcileForUser_stillRecords_whenTheRunReclassifiedSomething() {
        UUID accountId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 10);
        Transaction original = txn(UUID.randomUUID(), accountId, date, new BigDecimal("486.00"),
                Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR", Instant.parse("2026-07-10T10:00:00Z"));
        Transaction duplicate = txn(UUID.randomUUID(), accountId, date, new BigDecimal("486.00"),
                Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR", Instant.parse("2026-07-10T10:05:00Z"));
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(original, duplicate));

        reconciliationService.reconcileForUser(userId);

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> metadataCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        org.mockito.Mockito.verify(auditService).record(
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.eq("RECONCILIATION_RUN"),
                org.mockito.ArgumentMatchers.eq("Transaction"), org.mockito.ArgumentMatchers.isNull(),
                metadataCaptor.capture());

        assertThat(metadataCaptor.getValue())
                .containsEntry("duplicatesFound", 1)
                .containsEntry("recordedBecause", "reclassified");
    }

    /**
     * Phase 2, docs/proposals/reconciliation-evolution-roadmap-proposal.md, Part 3. Each of the
     * four passes now dual-writes a {@link TransactionRelationship} edge alongside its legacy
     * column -- these pin down the exact (from, to, type) shape each pass writes, since a wrong
     * direction here would be wrong for every one of these edges going forward, not just one test.
     */
    @Test
    void reconcileForUser_alsoWritesADuplicateEdge() {
        UUID accountId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 10);
        Transaction original = txn(UUID.randomUUID(), accountId, date, new BigDecimal("486.00"),
                Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR", Instant.parse("2026-07-10T10:00:00Z"));
        Transaction duplicate = txn(UUID.randomUUID(), accountId, date, new BigDecimal("486.00"),
                Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR", Instant.parse("2026-07-10T10:05:00Z"));
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(original, duplicate));

        reconciliationService.reconcileForUser(userId);

        List<TransactionGraphService.PendingEdge> edges = capturePendingEdges();
        assertThat(edges).hasSize(1);
        TransactionGraphService.PendingEdge edge = edges.get(0);
        assertThat(edge.userId()).isEqualTo(userId);
        assertThat(edge.fromTransactionId()).isEqualTo(duplicate.getId());
        assertThat(edge.toTransactionId()).isEqualTo(original.getId());
        assertThat(edge.relationshipType()).isEqualTo(TransactionRelationship.RelationshipType.DUPLICATE);
        assertThat(edge.matchedAmount()).isEqualByComparingTo("486.00");
        assertThat(edge.status()).isEqualTo(TransactionRelationship.Status.AUTO_CONFIRMED);
        assertThat(edge.detectionMethod()).isEqualTo(TransactionRelationship.DetectionMethod.RULE_ENGINE);
    }

    @Test
    void reconcileForUser_alsoWritesATransferEdge_inBothDirections() {
        UUID savings = UUID.randomUUID();
        UUID creditCard = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 10);
        Transaction payment = txn(UUID.randomUUID(), savings, date, new BigDecimal("5000.00"),
                Transaction.Type.EXPENSE, "CC PAYMENT XXXX1234", Instant.parse("2026-07-10T10:00:00Z"));
        Transaction credit = txn(UUID.randomUUID(), creditCard, date, new BigDecimal("5000.00"),
                Transaction.Type.INCOME, "PAYMENT RECEIVED", Instant.parse("2026-07-10T10:05:00Z"));
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(payment, credit));

        reconciliationService.reconcileForUser(userId);

        List<TransactionGraphService.PendingEdge> edges = capturePendingEdges();
        assertThat(edges).hasSize(2);
        assertThat(edges).anySatisfy(e -> {
            assertThat(e.fromTransactionId()).isEqualTo(payment.getId());
            assertThat(e.toTransactionId()).isEqualTo(credit.getId());
            assertThat(e.relationshipType()).isEqualTo(TransactionRelationship.RelationshipType.TRANSFER);
        });
        assertThat(edges).anySatisfy(e -> {
            assertThat(e.fromTransactionId()).isEqualTo(credit.getId());
            assertThat(e.toTransactionId()).isEqualTo(payment.getId());
            assertThat(e.relationshipType()).isEqualTo(TransactionRelationship.RelationshipType.TRANSFER);
        });
    }

    @Test
    void reconcileForUser_alsoWritesARefundEdge() {
        UUID accountId = UUID.randomUUID();
        Transaction purchase = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 1),
                new BigDecimal("999.00"), Transaction.Type.EXPENSE, "AMAZON ORDER 1122",
                Instant.parse("2026-07-01T10:00:00Z"));
        Transaction refund = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 5),
                new BigDecimal("999.00"), Transaction.Type.INCOME, "AMAZON REFUND 1122",
                Instant.parse("2026-07-05T10:00:00Z"));
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(purchase, refund));

        reconciliationService.reconcileForUser(userId);

        List<TransactionGraphService.PendingEdge> edges = capturePendingEdges();
        assertThat(edges).hasSize(1);
        TransactionGraphService.PendingEdge edge = edges.get(0);
        assertThat(edge.fromTransactionId()).isEqualTo(refund.getId());
        assertThat(edge.toTransactionId()).isEqualTo(purchase.getId());
        assertThat(edge.relationshipType()).isEqualTo(TransactionRelationship.RelationshipType.REFUND);
        assertThat(edge.matchedAmount()).isEqualByComparingTo("999.00");
    }

    @Test
    void reconcileForUser_alsoWritesAReversalEdge_distinctFromRefund() {
        UUID accountId = UUID.randomUUID();
        Transaction purchase = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 1),
                new BigDecimal("500.00"), Transaction.Type.EXPENSE, "MERCHANT PAYMENT 1122",
                Instant.parse("2026-07-01T10:00:00Z"));
        Transaction reversal = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 3),
                new BigDecimal("500.00"), Transaction.Type.INCOME, "PAYMENT REVERSED 1122",
                Instant.parse("2026-07-03T10:00:00Z"));
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(purchase, reversal));

        reconciliationService.reconcileForUser(userId);

        List<TransactionGraphService.PendingEdge> edges = capturePendingEdges();
        assertThat(edges).hasSize(1);
        TransactionGraphService.PendingEdge edge = edges.get(0);
        assertThat(edge.fromTransactionId()).isEqualTo(reversal.getId());
        assertThat(edge.toTransactionId()).isEqualTo(purchase.getId());
        assertThat(edge.relationshipType()).isEqualTo(TransactionRelationship.RelationshipType.REVERSAL);
        assertThat(edge.matchedAmount()).isEqualByComparingTo("500.00");
    }

    @SuppressWarnings("unchecked")
    private List<TransactionGraphService.PendingEdge> capturePendingEdges() {
        org.mockito.ArgumentCaptor<List<TransactionGraphService.PendingEdge>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(transactionGraphService).linkAll(captor.capture());
        return captor.getValue();
    }
}
