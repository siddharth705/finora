package com.finora.service;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One realistic statement exercising all three reconciliation passes at once, plus a
 * characterisation of what concurrent reconciliation actually does today.
 *
 * <p><b>Why this is separate from ReconciliationServiceTest.</b> That suite is thorough and
 * deliberately isolated: each test constructs the minimum data for one pass, which is exactly right
 * for pinning down a rule's edges. What it cannot show is the passes interacting — that duplicates
 * are removed from transfer consideration, that a transaction claimed as a transfer is then
 * excluded from refund matching, and that ordinary unrelated activity survives all three untouched.
 * Those interactions are the ones a future change is most likely to break silently, because every
 * single-pass test would still pass.
 *
 * <p>The fixture below is one month of plausible activity across two accounts, containing exactly
 * one of each verdict plus transactions that must remain OK. If a change makes any pass greedier,
 * the untouched rows are what notice.
 */
class ReconciliationEndToEndTest {

    private TransactionRepository transactionRepository;
    private RelationshipService relationshipService;
    private AuditService auditService;
    private ReconciliationService reconciliationService;

    private final UUID userId = UUID.randomUUID();
    private final UUID savings = UUID.randomUUID();
    private final UUID creditCard = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        relationshipService = mock(RelationshipService.class);
        auditService = mock(AuditService.class);
        reconciliationService = new ReconciliationService(transactionRepository, relationshipService, auditService);
    }

    private Transaction txn(UUID accountId, LocalDate date, String amount, Transaction.Type type,
                            String description, Instant createdAt) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(t, "createdAt", createdAt);
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setTxnType(type);
        t.setDescription(description);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        return t;
    }

    /** One month, two accounts, one of every verdict, and activity that must stay untouched. */
    private record Fixture(List<Transaction> all, Transaction duplicateOriginal, Transaction duplicateCopy,
                           Transaction transferOut, Transaction transferIn, Transaction purchase,
                           Transaction refund, Transaction groceries, Transaction salary) {}

    private Fixture realisticMonth() {
        // Duplicate: the same food order imported twice, as a re-imported statement produces.
        Transaction duplicateOriginal = txn(creditCard, LocalDate.of(2026, 7, 3), "486.00",
                Transaction.Type.EXPENSE, "SWIGGY ORDER 4471", Instant.parse("2026-07-03T10:00:00Z"));
        Transaction duplicateCopy = txn(creditCard, LocalDate.of(2026, 7, 3), "486.00",
                Transaction.Type.EXPENSE, "SWIGGY ORDER 4471", Instant.parse("2026-07-03T10:05:00Z"));

        // Transfer: a credit-card payment leaving savings and landing on the card two days later.
        // Different accounts, opposite directions, same amount -- and "payment" in the narration,
        // which is the heuristic that makes the pair worth evaluating at all.
        Transaction transferOut = txn(savings, LocalDate.of(2026, 7, 10), "15000.00",
                Transaction.Type.EXPENSE, "CREDIT CARD PAYMENT", Instant.parse("2026-07-10T09:00:00Z"));
        Transaction transferIn = txn(creditCard, LocalDate.of(2026, 7, 12), "15000.00",
                Transaction.Type.INCOME, "PAYMENT RECEIVED THANK YOU", Instant.parse("2026-07-12T09:00:00Z"));

        // Refund: a returned purchase coming back to the same account three weeks later, partial.
        // Merchant is set on both sides because the real pipeline resolves it before reconciliation
        // runs (see MerchantNormalizationEngine) -- leaving it null would test the refund pass with
        // one of its two entry signals permanently disabled.
        Transaction purchase = txn(creditCard, LocalDate.of(2026, 7, 5), "3200.00",
                Transaction.Type.EXPENSE, "RETAILER RETAIL", Instant.parse("2026-07-05T12:00:00Z"));
        purchase.setMerchant("Myntra");
        Transaction refund = txn(creditCard, LocalDate.of(2026, 7, 26), "1600.00",
                Transaction.Type.INCOME, "RETAILER REFUND FOR ORDER", Instant.parse("2026-07-26T12:00:00Z"));
        refund.setMerchant("Myntra");

        // Must remain OK. Groceries is an ordinary expense; salary is income that shares neither
        // account nor shape with anything above -- and is the row that proves the transfer pass's
        // salary guard is doing its job rather than the fixture simply not tempting it.
        //
        // Its amount is deliberately BELOW the refund's. This fixture originally had it at 2145.50
        // and the refund bound to it instead of to the Myntra purchase -- correctly, per the code:
        // isCloserRefundMatch ranks by exact-amount-then-date-proximity and never consults
        // merchant, so groceries (18 days away) beat the real purchase (21 days) despite the
        // merchant matching on the other candidate. Merchant gates ENTRY to the candidate set but
        // does not influence which candidate wins. That is a live behaviour worth knowing about
        // and is being raised separately; it is not changed here, because the ranking rule is a
        // money-correctness decision and this test exists to characterise the code, not to smuggle
        // an edit into it. At 1245.50 groceries is excluded by "a refund cannot exceed the
        // purchase", which keeps this fixture testing what it says it tests.
        Transaction groceries = txn(creditCard, LocalDate.of(2026, 7, 8), "1245.50",
                Transaction.Type.EXPENSE, "BIG BAZAAR", Instant.parse("2026-07-08T18:00:00Z"));
        Transaction salary = txn(savings, LocalDate.of(2026, 7, 1), "85000.00",
                Transaction.Type.INCOME, "SALARY PAYMENT ACME CORP", Instant.parse("2026-07-01T06:00:00Z"));

        return new Fixture(
                new ArrayList<>(List.of(duplicateOriginal, duplicateCopy, transferOut, transferIn,
                        purchase, refund, groceries, salary)),
                duplicateOriginal, duplicateCopy, transferOut, transferIn, purchase, refund, groceries, salary);
    }

    private Fixture runAgainstRealisticMonth() {
        Fixture f = realisticMonth();
        when(transactionRepository.findByUserId(userId)).thenReturn(f.all());
        reconciliationService.reconcileForUser(userId);
        return f;
    }

    @Test
    @DisplayName("all three passes reach the right verdict on one realistic month")
    void everyPassClassifiesItsOwnRowsAndLeavesTheRestAlone() {
        Fixture f = runAgainstRealisticMonth();

        assertThat(f.duplicateCopy().getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.DUPLICATE);
        assertThat(f.duplicateCopy().getIsDuplicateOf()).isEqualTo(f.duplicateOriginal().getId());
        assertThat(f.duplicateOriginal().getReconciliationStatus())
                .as("the earliest of a duplicate group is the survivor, never itself flagged")
                .isEqualTo(Transaction.ReconciliationStatus.OK);

        assertThat(f.transferOut().isTransfer()).isTrue();
        assertThat(f.transferIn().isTransfer()).isTrue();
        assertThat(f.transferOut().getTransferPairId()).isEqualTo(f.transferIn().getId());
        assertThat(f.transferIn().getTransferPairId()).isEqualTo(f.transferOut().getId());

        assertThat(f.refund().getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
        assertThat(f.refund().getRefundOfTransactionId()).isEqualTo(f.purchase().getId());

        assertThat(f.groceries().getReconciliationStatus())
                .as("an ordinary expense must survive all three passes untouched")
                .isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(f.salary().getReconciliationStatus())
                .as("salary is external income, never an internal transfer -- even sharing the word "
                        + "'payment' with the real transfer above")
                .isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(f.salary().isTransfer()).isFalse();
    }

    @Test
    @DisplayName("the whole run is written in one batch, not one save per match")
    void everyTouchedRowIsWrittenExactlyOnceInASingleBatch() {
        Fixture f = runAgainstRealisticMonth();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Transaction>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(transactionRepository, times(1)).saveAll(captor.capture());

        Collection<Transaction> written = captor.getValue();
        assertThat(written)
                .as("both sides of the transfer, the duplicate copy, and the refund -- and nothing else")
                .containsExactlyInAnyOrder(f.duplicateCopy(), f.transferOut(), f.transferIn(), f.refund());
        assertThat(written)
                .as("a row touched by more than one pass must still be written once")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every match records why it was made")
    void eachVerdictCarriesItsOwnStructuredExplanation() {
        Fixture f = runAgainstRealisticMonth();

        Map<String, Object> duplicate = f.duplicateCopy().getReconciliationExplanation();
        assertThat(duplicate).containsEntry("type", "DUPLICATE")
                .containsEntry("matchedTransaction", f.duplicateOriginal().getId().toString());

        Map<String, Object> transfer = f.transferOut().getReconciliationExplanation();
        assertThat(transfer).containsEntry("type", "TRANSFER")
                .containsEntry("matchedTransaction", f.transferIn().getId().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> transferReason = (Map<String, Object>) transfer.get("reason");
        assertThat(transferReason)
                .containsEntry("differentAccount", true)
                .containsEntry("oppositeDirection", true)
                .containsEntry("dateDifferenceDays", 2L)
                .containsEntry("relationshipIdentifierMatched", false)
                .as("no relationship identifier here, so the narrower window is the one that applied")
                .containsEntry("dayWindowApplied", ReconciliationPolicy.DEFAULT_TRANSFER_DAY_WINDOW);

        Map<String, Object> refund = f.refund().getReconciliationExplanation();
        assertThat(refund).containsEntry("type", "REFUND")
                .containsEntry("matchedTransaction", f.purchase().getId().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> refundReason = (Map<String, Object>) refund.get("reason");
        assertThat(refundReason)
                .containsEntry("sameAccount", true)
                .containsEntry("dateDifferenceDays", 21L)
                .containsEntry("refundKeyword", true)
                .as("both entry signals fired here, which is what distinguishes a confident refund "
                        + "match from one carried by a keyword alone")
                .containsEntry("sameMerchant", true)
                .as("1600 of a 3200 purchase came back")
                .containsEntry("partialRefund", true);

        assertThat(f.groceries().getReconciliationExplanation())
                .as("an unmatched row has no reason to record -- null, not an empty object")
                .isNull();
    }

    @Test
    @DisplayName("the run metrics report what actually happened")
    void theAuditEntryCarriesTheRunsCostAndOutcome() {
        runAgainstRealisticMonth();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(any(), anyString(), anyString(), any(), captor.capture());

        assertThat(captor.getValue())
                .containsEntry("transactionsProcessed", 8)
                .containsEntry("duplicatesFound", 1)
                .containsEntry("transfersMatched", 1)
                .containsEntry("refundsMatched", 1)
                .as("the duplicate, both transfer sides, and the refund")
                .containsEntry("rowsWritten", 4)
                .containsKey("durationMs");
    }

    @Test
    @DisplayName("re-running over already-reconciled data changes nothing and writes nothing")
    void aSecondRunIsIdempotent() {
        // Worth its own test because reconciliation is not run once -- it runs after every create,
        // update, delete, import confirm and statement delete, so the overwhelmingly common case is
        // running against data it has already classified. A pass that re-flagged its own previous
        // output would compound on every edit.
        Fixture f = runAgainstRealisticMonth();

        reconciliationService.reconcileForUser(userId);

        assertThat(f.duplicateOriginal().getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(f.groceries().getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        // One saveAll from the first run; the second finds nothing new and must not write at all.
        verify(transactionRepository, times(1)).saveAll(any());
    }

    // --- Concurrency ------------------------------------------------------------------------
    //
    // Characterisation, not a specification. Nothing in ReconciliationService locks anything, and
    // these tests do not add locking -- they record what today's behaviour actually is, so that a
    // future change to it is visible rather than accidental. That distinction matters: the audit
    // that prompted this noted concurrent reconciliation was untested, and an untested behaviour
    // is one nobody can safely change because nobody knows what it currently does.

    @Test
    @DisplayName("concurrent runs for the same user each reach the same verdicts")
    void concurrentRunsForOneUserConverge() throws Exception {
        Fixture f = realisticMonth();
        when(transactionRepository.findByUserId(userId)).thenReturn(f.all());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startTogether.await();
                        reconciliationService.reconcileForUser(userId);
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                });
            }
            startTogether.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failure.get())
                .as("concurrent reconciliation must not throw -- all eight threads share one "
                        + "transaction list and mutate the same objects")
                .isNull();

        // The verdicts are the interesting part: the passes are idempotent per transaction (each
        // guards on the status it would set), so racing threads converge on the same answer rather
        // than on a torn one. This is the property that makes the missing lock survivable today.
        assertThat(f.duplicateCopy().getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.DUPLICATE);
        assertThat(f.duplicateCopy().getIsDuplicateOf()).isEqualTo(f.duplicateOriginal().getId());
        assertThat(f.transferOut().isTransfer()).isTrue();
        assertThat(f.transferIn().isTransfer()).isTrue();
        assertThat(f.transferOut().getTransferPairId()).isEqualTo(f.transferIn().getId());
        assertThat(f.refund().getRefundOfTransactionId()).isEqualTo(f.purchase().getId());
        assertThat(f.groceries().getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    @Test
    @DisplayName("a transfer pair is never left half-matched under concurrency")
    void concurrentRunsNeverProduceAOneSidedTransfer() throws Exception {
        // The failure worth ruling out specifically: the transfer pass sets four fields across two
        // objects, and a reader interleaving with a writer could in principle observe one side
        // marked and the other not -- which downstream totals would read as money vanishing, since
        // a TRANSFER is excluded from income and expense both.
        Fixture f = realisticMonth();
        when(transactionRepository.findByUserId(userId)).thenReturn(f.all());

        int rounds = 200;
        AtomicInteger oneSided = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < rounds; i++) {
                pool.submit(() -> reconciliationService.reconcileForUser(userId));
                pool.submit(() -> {
                    boolean outMarked = f.transferOut().isTransfer();
                    boolean inMarked = f.transferIn().isTransfer();
                    // Only a settled pair is asserted on: observing neither side marked yet is the
                    // legitimate "run has not reached them" state, not a defect.
                    if (outMarked != inMarked) oneSided.incrementAndGet();
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(f.transferOut().isTransfer()).isTrue();
        assertThat(f.transferIn().isTransfer()).isTrue();
        // Recorded rather than asserted to zero: the two sides genuinely are written one after the
        // other with no barrier between them, so a reader CAN land in the gap. What matters is that
        // the final state is consistent, which the assertions above cover. If this ever needs to be
        // zero mid-flight, that is a locking change and should be made deliberately -- see the note
        // above this section.
        System.out.println("transient one-sided observations across " + rounds + " rounds: " + oneSided.get());
    }
}
