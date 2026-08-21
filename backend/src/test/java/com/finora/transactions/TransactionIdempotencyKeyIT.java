package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Gap review of SEC-06 (docs/quality/bug-reports/2026-08-19-security-review-findings.md):
 * TransactionService.create()'s idempotency check found nothing here that a mocked
 * TransactionServiceTest could prove -- Transaction's own {@code @SQLRestriction} and a real
 * Postgres unique-index race are both invisible to a repository mock by construction, same
 * reasoning {@code MerchantLearningConfirmRaceIT}/{@code BulkRecategorizeLearningIT} give for why
 * they run against a real container rather than mocks.
 */
class TransactionIdempotencyKeyIT extends AbstractIntegrationTest {

    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** Real by default -- spied only to force two concurrent create() calls to genuinely overlap
     *  around their own inserts (see the race test below). */
    @MockitoSpyBean private CategorizationService categorizationService;

    private record Fixture(User user, Account account) {}

    private Fixture fixture(BigDecimal startingBalance) {
        User user = new User();
        user.setEmail("txn-idempotency-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Idempotency IT User");
        user.setPhoneVerified(false);
        User savedUser = userRepository.save(user);

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Idempotency IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(startingBalance);
        Account savedAccount = accountRepository.save(account);

        return new Fixture(savedUser, savedAccount);
    }

    private TransactionDto.CreateRequest request(Fixture f, BigDecimal amount, String description, String idempotencyKey) {
        return new TransactionDto.CreateRequest(f.account().getId(), "Groceries", LocalDate.of(2026, 8, 1),
                description, amount, "EXPENSE", List.of(), idempotencyKey);
    }

    /** Rows for a (user, key) pair as the database itself holds them, soft-deleted or not --
     *  {@code TransactionRepository.findByUserId} runs through Transaction's own
     *  {@code @SQLRestriction} and would silently under-count here, which is exactly the gap this
     *  whole test class exists to close (see the repository method's own doc comment). */
    private int rowCountForKey(UUID userId, String idempotencyKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, userId, idempotencyKey);
        return count == null ? 0 : count;
    }

    // --- Soft-delete interaction (Bug 1): a retry against a since-soft-deleted transaction must
    // resolve back to the same identity, not 500/409 on the still-present unique index, and must
    // not move the account balance a second time. ---

    @Test
    void retryingAfterTheOriginalWasSoftDeleted_resolvesToTheSameTransaction_ratherThanThrowing() {
        Fixture f = fixture(BigDecimal.valueOf(1000));
        var req = request(f, BigDecimal.valueOf(200), "Test transaction", "soft-delete-retry-key");

        TransactionDto original = transactionService.create(f.user().getId(), req);
        transactionService.delete(f.user().getId(), original.id(), f.user().getId());

        // The transaction is soft-deleted (Account.balance already reversed by delete()) -- a
        // client retrying the exact same request with the exact same key must not throw, and must
        // not insert a second row that collides with the deleted row's still-present
        // idempotency_key at V97's unique index.
        TransactionDto retried = transactionService.create(f.user().getId(), req);

        assertThat(retried.id()).isEqualTo(original.id());
        assertThat(rowCountForKey(f.user().getId(), "soft-delete-retry-key"))
                .as("no second row was inserted under the same key")
                .isEqualTo(1);

        // create()'s soft-deleted match is returned WITHOUT re-adjusting the balance -- delete()
        // already reversed the original's contribution, so the balance must sit exactly where
        // delete() left it, not be moved a third time by this retry.
        Account reloaded = accountRepository.findById(f.account().getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("1000");
    }

    @Test
    void retryingAfterTheOriginalWasSoftDeleted_withADifferentAmount_stillRejectsAsAConflict() {
        Fixture f = fixture(BigDecimal.valueOf(1000));
        var original = request(f, BigDecimal.valueOf(200), "Test transaction", "soft-delete-retry-key-2");
        TransactionDto created = transactionService.create(f.user().getId(), original);
        transactionService.delete(f.user().getId(), created.id(), f.user().getId());

        var replayedWithDifferentAmount = request(f, BigDecimal.valueOf(999), "Test transaction", "soft-delete-retry-key-2");

        // Bug 2's check applies here too: the soft-delete-aware lookup finding a match is not
        // enough on its own to replay it -- the fields still have to agree.
        assertThatThrownBy(() -> transactionService.create(f.user().getId(), replayedWithDifferentAmount))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already used for a different request");
    }

    // --- Concurrent submission race (Bug 4's acceptance criterion): two requests sharing a brand
    // new idempotency key, genuinely overlapping, must not both create a transaction. ---

    /**
     * Both callers are released from a {@link CyclicBarrier} inside
     * {@code CategorizationService.resolveOrCreateCategory} -- reached only after each caller's own
     * idempotency lookup has already found nothing (that lookup runs earlier in {@code create()}),
     * so releasing them together guarantees both proceed toward their own INSERT at the same time
     * instead of leaving the overlap to scheduler luck.
     *
     * <p>The two requests use deliberately different descriptions. Same-description requests each
     * resolve to the same merchant via {@code MerchantNormalizationEngine} (see
     * {@code MerchantConcurrentAliasRaceIT}), which is its own check-then-insert and would have the
     * first caller hold an uncommitted merchant row while parked at the barrier below -- blocking
     * the second caller inside merchant resolution, before it ever reaches the barrier, and
     * deadlocking the test against itself. Distinct descriptions route the two callers to distinct
     * merchants so the only thing they actually contend on is what this test means to exercise: the
     * shared idempotency key.
     */
    @Test
    void twoConcurrentCreatesWithTheSameNewIdempotencyKey_exactlyOneWins() throws Exception {
        Fixture f = fixture(BigDecimal.valueOf(1000));
        String sharedKey = "concurrent-race-key";

        // Pre-created so both callers' resolveOrCreateCategory is a plain SELECT hit, not a race
        // of its own -- Category carries a UNIQUE(user_id, name) with no ON CONFLICT handling, and
        // without this, two concurrent first-ever inserts of the same category name collide there
        // instead, which would prove that unrelated race rather than the one this test targets:
        // the transactions table's own idempotency_key unique index (V97).
        categorizationService.resolveOrCreateCategory(f.user().getId(), "Groceries");

        CyclicBarrier bothPastTheirOwnIdempotencyCheck = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothPastTheirOwnIdempotencyCheck.await(30, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(categorizationService).resolveOrCreateCategory(eq(f.user().getId()), eq("Groceries"));

        AtomicReference<TransactionDto> result1 = new AtomicReference<>();
        AtomicReference<TransactionDto> result2 = new AtomicReference<>();
        AtomicReference<Throwable> failure1 = new AtomicReference<>();
        AtomicReference<Throwable> failure2 = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                result1.set(transactionService.create(f.user().getId(),
                        request(f, BigDecimal.valueOf(200), "Race transaction A", sharedKey)));
            } catch (Throwable t) {
                failure1.set(t);
            }
        }, "txn-idempotency-race-1");
        Thread t2 = new Thread(() -> {
            try {
                result2.set(transactionService.create(f.user().getId(),
                        request(f, BigDecimal.valueOf(200), "Race transaction B", sharedKey)));
            } catch (Throwable t) {
                failure2.set(t);
            }
        }, "txn-idempotency-race-2");

        t1.start();
        t2.start();
        t1.join(TimeUnit.SECONDS.toMillis(30));
        t2.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(t1.isAlive()).as("the first caller must have finished, not hung").isFalse();
        assertThat(t2.isAlive()).as("the second caller must have finished, not hung").isFalse();

        long successCount = (result1.get() != null ? 1 : 0) + (result2.get() != null ? 1 : 0);
        assertThat(successCount).as("exactly one caller must win the race").isEqualTo(1);

        Throwable loserFailure = failure1.get() != null ? failure1.get() : failure2.get();
        assertThat(loserFailure)
                .as("the race loser must get a clean, typed conflict, never an unhandled exception")
                .isNotNull()
                .isInstanceOf(DataAccessException.class);

        assertThat(rowCountForKey(f.user().getId(), sharedKey))
                .as("never a duplicate transaction")
                .isEqualTo(1);

        // The winner's EXPENSE was applied exactly once -- the loser's failed, rolled-back
        // transaction must not have touched the balance at all.
        Account reloaded = accountRepository.findById(f.account().getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("800");
    }
}
