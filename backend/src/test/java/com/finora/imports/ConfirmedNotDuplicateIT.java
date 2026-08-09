package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WI5 follow-up: a decision the user makes has to survive the machinery that runs after them.
 *
 * <p>Found by the milestone validation gate, driving the real UI against a real database. A user
 * reviewed two identical METRO FARE charges, chose "Import anyway" -- the right answer, they
 * genuinely commute twice a day -- and the rows landed in the ledger. Then
 * {@link ReconciliationService}'s duplicate pass ran, saw two rows sharing a duplicate key, and
 * marked the later one {@code isDuplicateOf}. Every spend calculation filters that out, so the
 * ledger held Rs 1,618.50 while the dashboard reported Rs 1,528.50 -- the Rs 90 gap being exactly
 * the fares the user had asked for.
 *
 * <p>WI5 took silent auto-skipping out of the import screen. This takes it out of everything
 * downstream. Integration tests against real Postgres because the failure lived entirely in the
 * composition: import wrote the row correctly, reconciliation ran correctly by its own lights, and
 * the damage existed only once both had run in sequence against a committed database. A mocked
 * test of either half would have passed while the product was wrong.
 */
class ConfirmedNotDuplicateIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    private record Fixture(User user, Account account) {}

    private Fixture fixture() {
        User user = new User();
        user.setEmail("confirmed-not-dup-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Confirmed Not Duplicate IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Confirmed Not Duplicate IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return new Fixture(savedUser, accountRepository.save(account));
    }

    private MockMultipartFile statementFile() {
        return new MockMultipartFile("file", "fares.csv", "text/csv",
                "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One METRO FARE, imported the way the review screen would send it.
     *
     * @param flaggedByEngine what staging decided -- {@code likelyDuplicate}
     * @param confirmedByUser what the person decided -- "Import anyway" on the review screen
     */
    private void importFare(Fixture f, boolean flaggedByEngine, boolean confirmedByUser) throws Exception {
        ConfirmedRow row = new ConfirmedRow(
                LocalDate.of(2026, 7, 10), "METRO FARE", new BigDecimal("45.00"), "EXPENSE",
                "Transport", true, "rule", null, flaggedByEngine, null, null, confirmedByUser);

        importService.confirm(f.user().getId(), statementFile(),
                new ConfirmRequest(null, List.of(row), f.account().getId(), null, null, null,
                null));
    }

    private List<Transaction> fares(Fixture f) {
        return transactionRepository.findByUserId(f.user().getId()).stream()
                .filter(t -> "METRO FARE".equals(t.getDescription()))
                .toList();
    }

    /**
     * The defect, stated as the product claim it broke: what the ledger holds and what the spend
     * totals count are the same set of transactions.
     */
    @Test
    void aFareTheUserChoseToImportStillCountsAsSpending() throws Exception {
        Fixture f = fixture();
        importFare(f, false, false);   // the first fare -- nothing flagged it, nothing to decide
        importFare(f, true, true);     // the second, flagged and explicitly confirmed by the user

        List<Transaction> both = fares(f);
        assertThat(both).hasSize(2);

        BigDecimal inLedger = both.stream().map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal counted = both.stream().filter(t -> t.getIsDuplicateOf() == null)
                .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(inLedger).isEqualByComparingTo("90.00");
        assertThat(counted)
                .as("every spend calculation filters isDuplicateOf IS NULL, so this IS what the "
                        + "dashboard, budgets, reports and insights will show")
                .isEqualByComparingTo(inLedger);
    }

    /** The decision is recorded on the row, not merely acted on once. */
    @Test
    void theConfirmationIsPersistedOnTheTransaction() throws Exception {
        Fixture f = fixture();
        importFare(f, false, false);
        importFare(f, true, true);

        assertThat(fares(f)).filteredOn(t -> t.getNotDuplicateConfirmedAt() != null)
                .as("exactly the row the user ruled on carries the ruling")
                .hasSize(1);
    }

    /**
     * The half that makes the fix worth having. Reconciliation runs after every import, create,
     * edit and delete -- so a decision honoured only by the run that follows the import would be
     * quietly undone by the user's next unrelated action, which is a worse failure than the
     * original because it appears later and for no visible reason.
     */
    @Test
    void aLaterReconciliationRunDoesNotUndoTheDecision() throws Exception {
        Fixture f = fixture();
        importFare(f, false, false);
        importFare(f, true, true);

        reconciliationService.reconcileForUser(f.user().getId());
        reconciliationService.reconcileForUser(f.user().getId());

        assertThat(fares(f)).allSatisfy(t ->
                assertThat(t.getIsDuplicateOf())
                        .as("a confirmed row must stay confirmed across repeated runs")
                        .isNull());
    }

    /**
     * The guard has to be narrow or it is not a fix, it is a hole. A user who re-uploads the same
     * statement without deciding anything must still get duplicate detection.
     */
    @Test
    void anUnconfirmedRepeatIsStillFlagged() throws Exception {
        Fixture f = fixture();
        importFare(f, false, false);
        importFare(f, true, false);

        reconciliationService.reconcileForUser(f.user().getId());

        assertThat(fares(f)).filteredOn(t -> t.getIsDuplicateOf() != null)
                .as("nothing was confirmed, so the later copy is still the engine's to flag")
                .hasSize(1);
    }

    /**
     * A confirmed row stays in its group rather than being lifted out of it, so a third copy --
     * one nobody ruled on, e.g. the statement uploaded again by accident -- is still caught.
     * Excluding confirmed rows from grouping entirely would have been the simpler change and would
     * have silently disabled detection for every subsequent repeat.
     */
    @Test
    void aThirdUnconfirmedCopyIsStillCaughtAlongsideAConfirmedOne() throws Exception {
        Fixture f = fixture();
        importFare(f, false, false);
        importFare(f, true, true);
        importFare(f, true, false);

        reconciliationService.reconcileForUser(f.user().getId());

        List<Transaction> all = fares(f);
        assertThat(all).hasSize(3);
        assertThat(all).filteredOn(t -> t.getIsDuplicateOf() != null)
                .as("only the copy nobody ruled on")
                .hasSize(1);
        assertThat(all).filteredOn(t -> t.getNotDuplicateConfirmedAt() != null)
                .allSatisfy(t -> assertThat(t.getIsDuplicateOf()).isNull());
    }

    /**
     * A client cannot assert "not a duplicate" about a row nothing questioned. The flag only means
     * anything as an answer to the engine's own question; accepting it unconditionally would let
     * any caller opt individual rows out of duplicate detection for free.
     */
    @Test
    void theFlagIsIgnoredOnARowTheEngineNeverFlagged() throws Exception {
        Fixture f = fixture();
        importFare(f, false, true);

        assertThat(fares(f)).singleElement()
                .satisfies(t -> assertThat(t.getNotDuplicateConfirmedAt()).isNull());
    }

    /** An import that carries no decision at all -- every pre-WI5 client, and the mobile app,
     *  which has no duplicate review screen -- behaves exactly as it did before. */
    @Test
    void aClientThatSendsNoDecisionIsUnaffected() throws Exception {
        Fixture f = fixture();
        ConfirmedRow legacyShape = new ConfirmedRow(
                LocalDate.of(2026, 7, 10), "METRO FARE", new BigDecimal("45.00"), "EXPENSE",
                "Transport", true, "rule", null, true, null, null);

        importService.confirm(f.user().getId(), statementFile(),
                new ConfirmRequest(null, List.of(legacyShape), f.account().getId(), null, null, null,
                null));

        assertThat(fares(f)).singleElement()
                .satisfies(t -> assertThat(t.getNotDuplicateConfirmedAt()).isNull());
    }
}
