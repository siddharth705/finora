package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.MultiAccountConfirmRequest;
import com.finora.dto.ImportDto.MultiAccountConfirmResponse;
import com.finora.dto.ImportDto.SectionConfirm;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * BH-041's acceptance test: one reconciliation pass for a whole multi-section import, proven not to
 * cost anything the per-section loop was buying.
 *
 * <p>The scenario is the one that breaks if any part of the change is wrong — a consolidated
 * statement whose Savings section pays off its own Credit Card section, imported and then imported
 * again:
 *
 * <pre>
 *   Savings      −30,000  "CREDIT CARD PAYMENT"   ─┐
 *                                                  ├─ one transfer, two rows, two sections
 *   Credit Card  +30,000  "PAYMENT RECEIVED"      ─┘
 * </pre>
 *
 * <p>It holds five separate things at once, each of which a plausible implementation gets wrong on
 * its own:
 *
 * <ol>
 *   <li><b>Cross-account matching survives.</b> Scoping reconciliation to "the accounts in this
 *       import" would still pass this test on a first import and fail the moment one leg arrives in
 *       a different import — so the re-import half matters as much as the first half.</li>
 *   <li><b>Both sides are classified.</b> Not one side. See the note on the asymmetry below.</li>
 *   <li><b>Per-section reporting still means something.</b> The whole reason BH-041 was deferred
 *       was a belief that a single pass would empty these counters. It does not: each section has
 *       its own {@code StatementImport} and {@code tally()} reads flags scoped to it.</li>
 *   <li><b>BH-003 is intact.</b> The duplicate-balance reversal travels in the same phase as the
 *       tally. Split them and re-importing moves the balance twice — the CLOSED–VERIFIED bug where
 *       a card went 4000.00 to 3000.00.</li>
 *   <li><b>Reconciliation runs once.</b> Which is the optimisation itself.</li>
 * </ol>
 *
 * <h2>The asymmetry this fixes</h2>
 *
 * <p>Before BH-041, section 1 was summarised before section 2 was written, so the transfer did not
 * exist yet when the Savings section reported. The pair came out as {@code transfersIdentified}
 * 0 for Savings and 1 for Credit Card — the same transfer, counted on one side only. Reconciling
 * after every section is persisted makes it 1 and 1, which is why the assertion below checks both.
 */
class MultiSectionSharedTransferIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    @SpyBean private ReconciliationService reconciliationService;
    @SpyBean private RecurringService recurringService;

    private static final LocalDate WHEN = LocalDate.of(2026, 7, 10);
    private static final BigDecimal AMOUNT = new BigDecimal("30000.00");
    private static final BigDecimal SAVINGS_OPENING = new BigDecimal("100000.00");
    private static final BigDecimal CARD_OPENING = new BigDecimal("30000.00");
    private static final byte[] FILE = "consolidated-statement".getBytes(StandardCharsets.UTF_8);

    private final List<UUID> createdUserIds = new ArrayList<>();

    /** Same reasoning as MultiSectionReconciliationCostIT's: the learning worker is off in the test
     *  profile, so confirms leave queued events in a table every test in the JVM shares. Scoped to
     *  this class's own users rather than truncating. */
    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private User user() {
        User user = new User();
        user.setEmail("multi-transfer-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Multi Section Transfer User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private Account account(User owner, String name, Account.Type type, BigDecimal balance) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName(name);
        account.setAccountType(type);
        account.setBalance(balance);
        return accountRepository.save(account);
    }

    private ConfirmedRow confirmed(String description, String type) {
        return new ConfirmedRow(WHEN, description, AMOUNT, type, "Other", true, "rule",
                null, false, null, null, false);
    }

    private StagedRow staged(String description, String type) {
        return new StagedRow(WHEN, description, AMOUNT, type, "Other", "rule",
                null, false, null, null);
    }

    /**
     * A two-section session, staged the way the PDF path stages one. detectedAccount is null on
     * purpose -- confirmMultiSection reads only {@code rows().size()} off the staged sections, and
     * building two full DetectedAccountInfo records would be fixture noise asserting nothing.
     */
    private ImportSession twoSectionSession(User user) {
        StagedAccountSection savings = new StagedAccountSection(
                null, List.of(staged("CREDIT CARD PAYMENT", "EXPENSE")), 1, 0, List.of());
        StagedAccountSection card = new StagedAccountSection(
                null, List.of(staged("PAYMENT RECEIVED THANK YOU", "INCOME")), 1, 0, List.of());
        return importSessionService.createMultiSection(
                user.getId(), "consolidated.pdf", FILE, List.of(savings, card));
    }

    private MultiAccountConfirmResponse confirmBothSections(User user, Account savings, Account card) {
        ImportSession session = twoSectionSession(user);
        return importService.confirmMultiSection(user.getId(), new MultiAccountConfirmRequest(
                session.getId(),
                List.of(
                        // Opening/closing left null on both sections so the balance follows the
                        // imported rows (the netDelta branch). That is the branch BH-003 guards --
                        // an authoritative closing balance is absolute and re-importing simply
                        // rewrites the same figure, which was never the bug.
                        new SectionConfirm(List.of(confirmed("CREDIT CARD PAYMENT", "EXPENSE")),
                                savings.getId(), null, null, null),
                        new SectionConfirm(List.of(confirmed("PAYMENT RECEIVED THANK YOU", "INCOME")),
                                card.getId(), null, null, null))));
    }

    private static final BigDecimal SUBSCRIPTION = new BigDecimal("499.00");

    private ConfirmedRow subscription(LocalDate on) {
        return new ConfirmedRow(on, "NETFLIX SUBSCRIPTION", SUBSCRIPTION, "EXPENSE", "Other",
                true, "rule", null, false, null, null, false);
    }

    private StagedRow subscriptionStaged(LocalDate on) {
        return new StagedRow(on, "NETFLIX SUBSCRIPTION", SUBSCRIPTION, "EXPENSE", "Other", "rule",
                null, false, null, null);
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("BH-041: a Savings→Card transfer across two sections, reconciled once, re-imported without corrupting either balance")
    void oneReconciliationPassStillFindsTheCrossSectionTransferAndSurvivesAReImport() {
        User user = user();
        Account savings = account(user, "Savings", Account.Type.SAVINGS, SAVINGS_OPENING);
        Account card = account(user, "Credit Card", Account.Type.CREDIT_CARD, CARD_OPENING);

        clearInvocations(reconciliationService);
        MultiAccountConfirmResponse first = confirmBothSections(user, savings, card);

        // (1) + (2) The transfer is found across two accounts, and BOTH rows carry it.
        //
        // Asserted BEFORE the call-count check below, deliberately. Both fail against the
        // pre-BH-041 shape, but the outcome is the interesting one: a reviewer who sees only "the
        // spy was called twice" learns that something moved, while this says what the user would
        // actually have seen wrong. Mechanism assertions are worth little if the behaviour
        // assertion never gets a chance to run.
        List<Transaction> all = transactionRepository.findByUserId(user.getId());
        assertThat(all).hasSize(2);
        assertThat(all).allSatisfy(t -> {
            assertThat(t.isTransfer())
                    .as("both legs of a transfer are transfers -- a one-sided match means the pass "
                            + "ran before the other section existed")
                    .isTrue();
            assertThat(t.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.TRANSFER);
            assertThat(t.getTransferPairId()).isNotNull();
        });
        assertThat(all.get(0).getTransferPairId()).isEqualTo(all.get(1).getId());
        assertThat(all.get(1).getTransferPairId()).isEqualTo(all.get(0).getId());

        // (3) Per-section reporting survives a shared pass. Both sections report the transfer --
        // before BH-041 this was 0 for the section that was summarised first.
        assertThat(first.perAccount()).hasSize(2);
        assertThat(first.perAccount()).allSatisfy(section -> {
            assertThat(section.imported()).isEqualTo(1);
            assertThat(section.transfersIdentified())
                    .as("each section sees the transfer its own row is half of")
                    .isEqualTo(1);
            assertThat(section.duplicatesDetected())
                    .as("nothing to duplicate on a first import")
                    .isZero();
        });

        // (5) The optimisation itself: ONE pass for the whole import, not one per section. And it
        // is the windowed entry point -- reconcileForUser stays untouched for its other seven
        // callers, which is the constraint the change was accepted under.
        verify(reconciliationService, times(1)).reconcileForImport(eq(user.getId()), any(), any());
        verify(reconciliationService, never()).reconcileForUser(any());

        // The balance moved by what was imported: savings paid out, the card's debt went down.
        BigDecimal savingsAfterFirst = SAVINGS_OPENING.subtract(AMOUNT);
        BigDecimal cardAfterFirst = CARD_OPENING.subtract(AMOUNT);
        assertThat(balanceOf(savings)).isEqualByComparingTo(savingsAfterFirst);
        assertThat(balanceOf(card))
                .as("a card balance is money OWED, so a payment reduces it")
                .isEqualByComparingTo(cardAfterFirst);

        // --- the same statement, imported a second time ---
        clearInvocations(reconciliationService);
        MultiAccountConfirmResponse second = confirmBothSections(user, savings, card);

        verify(reconciliationService, times(1)).reconcileForImport(eq(user.getId()), any(), any());

        assertThat(second.perAccount()).hasSize(2);
        assertThat(second.perAccount()).allSatisfy(section ->
                assertThat(section.duplicatesDetected())
                        .as("the re-imported row duplicates the one already on file")
                        .isEqualTo(1));

        // (4) BH-003. Both balances are exactly where the first import left them. If the tally and
        // the reversal had been separated by the persist/summarise split, each account would be off
        // by 30,000 here -- silently, with the rows that caused it hidden from the ledger view.
        assertThat(balanceOf(savings))
                .as("re-importing must not move the balance a second time")
                .isEqualByComparingTo(savingsAfterFirst);
        assertThat(balanceOf(card))
                .as("and the liability side has to hold too -- the inversion is where BH-004 lived")
                .isEqualByComparingTo(cardAfterFirst);
    }

    /**
     * BH-041 hoisted {@code recurringService.detectForUser} alongside reconciliation, and that was a
     * deliberate decision rather than code drifting with the block it lived in. Recording why, and
     * proving the behaviour, because it went slightly beyond the approved wording of the ticket.
     *
     * <h2>Why it moved</h2>
     *
     * <p>It sat in the same per-section tail and had the same shape — user-wide, once per section —
     * so a 3-section import ran it three times over the whole history. The measurement that
     * justified BH-041 counted those passes explicitly ("+2 recurring passes" alongside "+2
     * reconcile passes"); leaving it per-section would have left a large share of the repetition
     * the ticket exists to remove.
     *
     * <h2>Why it is safe</h2>
     *
     * <p>Not "the last pass saw everything" — something stronger. {@code detectForUser} begins by
     * resetting {@code setRecurring(false)} on every active transaction and then re-derives every
     * pattern from scratch. It is a full recomputation over current state, so it is idempotent:
     * running it three times and running it once reach the same result, and only the final run's
     * output survives. Its ordering dependency is preserved too — it filters out transfers and
     * duplicates, so it must run AFTER reconciliation, and {@code reconcileAcross} keeps that order.
     *
     * <p>What did change is the audit trail: one {@code RECURRING_DETECTION_RUN} row per import
     * instead of one per section. Same class of change as reconciliation's, and deliberate.
     *
     * <h2>What this test pins</h2>
     *
     * <p>That a recurring pattern whose occurrences are SPLIT ACROSS SECTIONS is still found. That
     * is the case a single shared pass has to get right, and it is the one a reader would reasonably
     * worry about after learning detection stopped running per section.
     */
    @Test
    @DisplayName("BH-041: a recurring pattern split across two sections is detected by the single shared pass")
    void recurringDetectionRunsOnceAndStillSeesAPatternSpanningSections() {
        User user = user();
        Account savings = account(user, "Savings", Account.Type.SAVINGS, SAVINGS_OPENING);
        Account card = account(user, "Credit Card", Account.Type.CREDIT_CARD, CARD_OPENING);

        // Three monthly charges to one merchant, two on the savings section and the third on the
        // card section. Grouping is by merchant across accounts, so this is one pattern that no
        // single section can see on its own.
        List<LocalDate> savingsDates = List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 5));
        LocalDate cardDate = LocalDate.of(2026, 3, 5);

        StagedAccountSection stagedSavings = new StagedAccountSection(
                null, savingsDates.stream().map(d -> subscriptionStaged(d)).toList(), 2, 0, List.of());
        StagedAccountSection stagedCard = new StagedAccountSection(
                null, List.of(subscriptionStaged(cardDate)), 1, 0, List.of());
        ImportSession session = importSessionService.createMultiSection(
                user.getId(), "consolidated.pdf", FILE, List.of(stagedSavings, stagedCard));

        clearInvocations(recurringService);
        importService.confirmMultiSection(user.getId(), new MultiAccountConfirmRequest(
                session.getId(),
                List.of(
                        new SectionConfirm(savingsDates.stream().map(d -> subscription(d)).toList(),
                                savings.getId(), null, null, null),
                        new SectionConfirm(List.of(subscription(cardDate)), card.getId(), null, null, null))));

        List<Transaction> all = transactionRepository.findByUserId(user.getId());
        assertThat(all).hasSize(3);
        assertThat(all)
                .as("all three charges belong to one monthly pattern, and two of them are in a "
                        + "different section from the third -- a per-section pass would have had to "
                        + "wait for the last section to see it, which is exactly why running once "
                        + "at the end is not a loss")
                .allSatisfy(t -> assertThat(t.isRecurring()).isTrue());

        verify(recurringService, times(1)).detectForUser(user.getId());
    }

    @Test
    @DisplayName("NEGATIVE: the shared pass still matches a leg that arrived in an EARLIER import")
    void aTransferWhoseOtherLegCameFromADifferentImportIsStillMatched() {
        User user = user();
        Account savings = account(user, "Savings", Account.Type.SAVINGS, SAVINGS_OPENING);
        Account card = account(user, "Credit Card", Account.Type.CREDIT_CARD, CARD_OPENING);

        // The savings leg lands on its own, in its own import. Nothing to pair with yet.
        importService.confirm(user.getId(), "savings-only.csv", FILE,
                new com.finora.dto.ImportDto.ConfirmRequest(null,
                        List.of(confirmed("CREDIT CARD PAYMENT", "EXPENSE")), savings.getId(), null, null, null));

        assertThat(transactionRepository.findByUserId(user.getId()))
                .as("one leg alone is not a transfer")
                .allSatisfy(t -> assertThat(t.isTransfer()).isFalse());

        // The card leg arrives later, in a completely separate import. This is the case that
        // account-scoped reconciliation would have broken: the savings account is not part of this
        // import at all, so scoping by account would never load the row to match against, and a
        // 30,000 payment would stay classified as real money movement.
        importService.confirm(user.getId(), "card-only.csv", FILE,
                new com.finora.dto.ImportDto.ConfirmRequest(null,
                        List.of(confirmed("PAYMENT RECEIVED THANK YOU", "INCOME")), card.getId(), null, null, null));

        assertThat(transactionRepository.findByUserId(user.getId()))
                .hasSize(2)
                .allSatisfy(t -> assertThat(t.isTransfer())
                        .as("the window spans every account the user has, not just the imported one")
                        .isTrue());
    }
}
