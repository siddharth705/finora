package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code bulkDelete} promises, asserted as an outcome rather than as a call.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code TransactionServiceTest}'s bulk cases mock the repository and stub {@code findById} per
 * id. They are useful, and they are pinned to the implementation: BH-057 changed one bulk fetch for
 * an equivalent one and four of them broke without a single behaviour changing. A test that a
 * refactor breaks, and that a genuine regression might not, is not covering the contract.
 *
 * <p>The contract is: <b>the requested transactions, owned by the caller, are gone; the money is
 * put back; nothing belonging to anyone else is touched.</b> None of that mentions a repository
 * method, and all of it is what a user would notice. Every assertion below protects one of those
 * clauses -- there are deliberately no assertions here that merely raise coverage.
 *
 * <p>Integration rather than unit for the same reason {@code NotDuplicateConfirmationIT} is: soft
 * delete is enforced by {@code @SQLRestriction} on the entity, the balance reversal is a second
 * write, and reconciliation pointers are cleared by a third. A mock proves none of those.
 */
class BulkDeleteBehaviourIT extends AbstractIntegrationTest {

    @Autowired private TransactionService transactionService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();

    /** See BH-058: confirmed writes enqueue learning events the test profile never drains, and a
     *  class that leaves them behind breaks the queue's own concurrency tests. */
    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private record Fixture(User user, Account account) {}

    private Fixture fixture(String openingBalance) {
        User user = new User();
        user.setEmail("bulk-delete-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Bulk Delete IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);
        createdUserIds.add(savedUser.getId());

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Bulk Delete IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal(openingBalance));
        return new Fixture(savedUser, accountRepository.save(account));
    }

    /** Distinct descriptions and amounts, so nothing here is incidentally a duplicate of anything
     *  else -- this class is about deletion, and a reconciliation flag would confuse the balances. */
    private UUID addExpense(Fixture f, String description, String amount, int day) {
        return transactionService.create(f.user().getId(), new TransactionDto.CreateRequest(
                f.account().getId(), "Other", LocalDate.of(2026, 7, day), description,
                new BigDecimal(amount), "EXPENSE", null)).id();
    }

    private BigDecimal balanceOf(Fixture f) {
        return accountRepository.findById(f.account().getId()).orElseThrow().getBalance();
    }

    private List<UUID> liveTransactionIds(Fixture f) {
        return transactionRepository.findByUserId(f.user().getId()).stream()
                .map(Transaction::getId).toList();
    }

    @Test
    @DisplayName("the requested transactions are gone and the money is put back")
    void deletesExactlyWhatWasAskedForAndReversesItsEffectOnTheBalance() {
        Fixture f = fixture("5000.00");
        UUID coffee = addExpense(f, "COFFEE", "150.00", 1);
        UUID lunch = addExpense(f, "LUNCH", "400.00", 2);
        UUID rentDeposit = addExpense(f, "RENT DEPOSIT", "2000.00", 3);
        assertThat(balanceOf(f)).isEqualByComparingTo("2450.00");

        transactionService.bulkDelete(f.user().getId(), List.of(coffee, lunch));

        assertThat(liveTransactionIds(f))
                .as("the two requested are gone; the one that was not requested stays")
                .containsExactly(rentDeposit);
        assertThat(balanceOf(f))
                .as("550.00 of spending was undone, so the balance goes back up by exactly that")
                .isEqualByComparingTo("3000.00");
    }

    @Test
    @DisplayName("NEGATIVE: another user's transaction cannot be deleted, and nothing is deleted when one is refused")
    void refusesTheWholeBatchWhenAnyIdBelongsToSomeoneElse() {
        Fixture mine = fixture("5000.00");
        Fixture theirs = fixture("9000.00");
        UUID myCoffee = addExpense(mine, "COFFEE", "150.00", 1);
        UUID theirLunch = addExpense(theirs, "THEIR LUNCH", "400.00", 2);

        assertThatThrownBy(() -> transactionService.bulkDelete(mine.user().getId(), List.of(myCoffee, theirLunch)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .as("forbidden, not 'not found' -- the row exists and belongs to someone")
                        .isEqualTo(HttpStatus.FORBIDDEN));

        // The half that matters more than the exception: the refusal must be total. A partial
        // delete that removed the caller's own row before hitting the foreign one would leave the
        // caller's ledger changed by a request that reported failure.
        assertThat(liveTransactionIds(mine))
                .as("the caller's own transaction survives a refused batch")
                .containsExactly(myCoffee);
        assertThat(liveTransactionIds(theirs))
                .as("and the other user's is untouched, which is the point")
                .containsExactly(theirLunch);
        assertThat(balanceOf(mine)).isEqualByComparingTo("4850.00");
        assertThat(balanceOf(theirs)).isEqualByComparingTo("8600.00");
    }

    @Test
    @DisplayName("NEGATIVE: an id that does not exist refuses the batch rather than silently skipping it")
    void refusesTheWholeBatchWhenAnIdDoesNotExist() {
        Fixture f = fixture("5000.00");
        UUID coffee = addExpense(f, "COFFEE", "150.00", 1);
        UUID neverExisted = UUID.randomUUID();

        assertThatThrownBy(() -> transactionService.bulkDelete(f.user().getId(), List.of(coffee, neverExisted)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .as("not found, distinct from forbidden -- the two mean different things to a client")
                        .isEqualTo(HttpStatus.NOT_FOUND));

        assertThat(liveTransactionIds(f))
                .as("a batch naming something unknown deletes nothing -- silently skipping it would "
                        + "let a client believe it removed rows it never named correctly")
                .containsExactly(coffee);
        assertThat(balanceOf(f)).isEqualByComparingTo("4850.00");
    }

    @Test
    @DisplayName("NEGATIVE: an already-deleted id is not silently accepted a second time")
    void refusesAnIdThatHasAlreadyBeenDeleted() {
        Fixture f = fixture("5000.00");
        UUID coffee = addExpense(f, "COFFEE", "150.00", 1);
        UUID lunch = addExpense(f, "LUNCH", "400.00", 2);

        transactionService.bulkDelete(f.user().getId(), List.of(coffee));
        BigDecimal afterFirstDelete = balanceOf(f);

        // Soft delete means the row is still physically present, so "already deleted" is a state a
        // lookup has to be filtered for rather than an absence. Getting that wrong would let the
        // reversal run twice and move the balance by 150.00 again.
        assertThatThrownBy(() -> transactionService.bulkDelete(f.user().getId(), List.of(coffee, lunch)))
                .isInstanceOf(ApiException.class);

        assertThat(liveTransactionIds(f))
                .as("the second request named a deleted row, so it removed nothing")
                .containsExactly(lunch);
        assertThat(balanceOf(f))
                .as("and above all did not reverse the same 150.00 a second time")
                .isEqualByComparingTo(afterFirstDelete);
    }

    @Test
    @DisplayName("a credit card's balance moves the other way, because the money owed goes down")
    void reversalRespectsTheAccountTypeConvention() {
        // Deleting a card purchase reduces what is OWED. Asserted because the inversion lives in
        // AccountBalanceConvention and a bulk path that re-derived it by hand is exactly the bug
        // that class was created to prevent.
        User user = new User();
        user.setEmail("bulk-delete-card-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Bulk Delete Card User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);
        createdUserIds.add(savedUser.getId());

        Account card = new Account();
        card.setUserId(savedUser.getId());
        card.setName("Card");
        card.setAccountType(Account.Type.CREDIT_CARD);
        card.setBalance(new BigDecimal("2000.00"));
        Account savedCard = accountRepository.save(card);

        UUID purchase = transactionService.create(savedUser.getId(), new TransactionDto.CreateRequest(
                savedCard.getId(), "Other", LocalDate.of(2026, 7, 1), "ONLINE PURCHASE",
                new BigDecimal("300.00"), "EXPENSE", null)).id();
        assertThat(accountRepository.findById(savedCard.getId()).orElseThrow().getBalance())
                .as("a purchase increases what is owed")
                .isEqualByComparingTo("2300.00");

        transactionService.bulkDelete(savedUser.getId(), List.of(purchase));

        assertThat(accountRepository.findById(savedCard.getId()).orElseThrow().getBalance())
                .as("removing it reduces the debt back -- the opposite direction from a savings account")
                .isEqualByComparingTo("2000.00");
    }
}
