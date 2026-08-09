package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BH-027: two identical transactions the user really did make, and the way out.
 *
 * <p>{@code ReconciliationService}'s duplicate pass groups on account, date, amount and description
 * and flags every member of a group but the earliest. It cannot tell "the same statement uploaded
 * twice" from "two metro fares on one day" — which is precisely why
 * {@code notDuplicateConfirmedAt} exists. That field was writable from exactly ONE place in the
 * application, {@code ImportService.confirm}, reachable only from the import review screen. A user
 * who entered the pair by hand had the second one suppressed from every total on the very next
 * write, with no affordance anywhere to disagree.
 *
 * <p><b>An integration test, because the defect is an interaction rather than a calculation.</b>
 * The flagging happens in one service, the escape hatch is honoured in another, and the thing that
 * makes the fix real is that a LATER reconciliation pass does not undo it. A mocked repository
 * would happily return whatever each step was handed and prove none of that.
 */
class NotDuplicateConfirmationIT extends AbstractIntegrationTest {

    @Autowired private TransactionService transactionService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    private record Fixture(User user, Account account) {}

    private Fixture fixture() {
        User user = new User();
        user.setEmail("not-duplicate-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Not Duplicate IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Not Duplicate IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("5000.00"));
        return new Fixture(savedUser, accountRepository.save(account));
    }

    /** The commute: same fare, same day, same description, genuinely twice. */
    private TransactionDto addMetroFare(Fixture f) {
        return transactionService.create(f.user().getId(), new TransactionDto.CreateRequest(
                f.account().getId(), "Transport", LocalDate.of(2026, 7, 10), "METRO FARE",
                new BigDecimal("45.00"), "EXPENSE", null));
    }

    private List<Transaction> fares(Fixture f) {
        return transactionRepository.findByUserId(f.user().getId()).stream()
                .sorted(Comparator.comparing(Transaction::getCreatedAt))
                .toList();
    }

    @Test
    @DisplayName("BH-027: a genuine second identical charge can be kept, and stays kept")
    void aConfirmedNonDuplicateSurvivesLaterReconciliationPasses() {
        Fixture f = fixture();
        addMetroFare(f);
        addMetroFare(f);

        // Reconciliation runs on create(), so by now the second one is already suppressed. This is
        // the state the user is looking at, and before this change it was the permanent one.
        List<Transaction> both = fares(f);
        assertThat(both).hasSize(2);
        Transaction flagged = both.get(1);
        assertThat(flagged.getIsDuplicateOf())
                .as("the engine cannot tell two fares from one statement imported twice")
                .isEqualTo(both.get(0).getId());
        assertThat(flagged.getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.DUPLICATE);

        transactionService.confirmNotDuplicate(f.user().getId(), flagged.getId());

        Transaction kept = transactionRepository.findById(flagged.getId()).orElseThrow();
        assertThat(kept.getIsDuplicateOf())
                .as("stamping the flag alone would leave the row excluded until something else touched it")
                .isNull();
        assertThat(kept.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(kept.getNotDuplicateConfirmedAt()).isNotNull();

        // The half that matters. Every write path re-runs the full duplicate pass, so a decision
        // that is not durable is a decision that lasts until the user's next transaction.
        addMetroFare(f);

        assertThat(transactionRepository.findById(flagged.getId()).orElseThrow().getIsDuplicateOf())
                .as("a later pass must not overrule the human")
                .isNull();
    }

    @Test
    @DisplayName("a third accidental copy is still flagged -- the escape hatch is per row, not per group")
    void confirmingOneRowDoesNotDisableDetectionForTheRest() {
        Fixture f = fixture();
        addMetroFare(f);
        addMetroFare(f);

        List<Transaction> both = fares(f);
        transactionService.confirmNotDuplicate(f.user().getId(), both.get(1).getId());

        // A genuinely accidental third copy -- the case the pass exists for -- must still be caught.
        addMetroFare(f);

        List<Transaction> all = fares(f);
        assertThat(all).hasSize(3);
        assertThat(all.get(2).getIsDuplicateOf())
                .as("confirming one row is not a licence to stop checking the others")
                .isNotNull();
    }

    @Test
    @DisplayName("the balance does not move -- the flag governed reports, never the ledger")
    void confirmingDoesNotTouchTheAccountBalance() {
        Fixture f = fixture();
        addMetroFare(f);
        addMetroFare(f);

        BigDecimal beforeConfirming =
                accountRepository.findById(f.account().getId()).orElseThrow().getBalance();
        // Both fares were applied when they were created; a DUPLICATE flag never took one back off.
        assertThat(beforeConfirming).isEqualByComparingTo("4910.00");

        transactionService.confirmNotDuplicate(f.user().getId(), fares(f).get(1).getId());

        assertThat(accountRepository.findById(f.account().getId()).orElseThrow().getBalance())
                .as("this decision changes what the REPORTS exclude; the money already moved")
                .isEqualByComparingTo(beforeConfirming);
    }

    @Test
    @DisplayName("another user's transaction cannot be confirmed")
    void ownershipIsEnforced() {
        Fixture mine = fixture();
        Fixture theirs = fixture();
        addMetroFare(theirs);
        UUID theirTransaction = fares(theirs).get(0).getId();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> transactionService.confirmNotDuplicate(mine.user().getId(), theirTransaction))
                .isInstanceOf(com.finora.exception.ApiException.class);
    }
}
