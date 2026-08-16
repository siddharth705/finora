package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantAlias;
import com.finora.entity.User;
import com.finora.repository.MerchantAliasRepository;
import com.finora.repository.UserRepository;
import com.finora.util.CategoryRules;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Found while investigating a DIFFERENT reported race (CSV import staging, which turned out not to
 * reproduce). Along the way, {@code addAlias}'s "lost the race, keep the existing row" recovery path
 * was checked against real Postgres and did not survive contact with it -- see
 * {@code MerchantNormalizationEngine.addAlias}'s own doc comment ("Bug fix, second") for the full
 * finding. In short: {@code addAlias} is only ever called from
 * {@link MerchantNormalizationEngine#resolve}, which is {@code @Transactional} with the default
 * (REQUIRED) propagation, so on the real call path ({@code ImportService.confirm} looping
 * {@code resolve()} once per staged row) it shares ONE ambient transaction with every other row in the
 * statement. The old recovery re-queried INSIDE that same transaction after a failed insert had
 * already poisoned it -- checked by hand against this project's own Postgres
 * ({@code docker exec ... psql}): once any statement in an open transaction fails, every LATER
 * statement on it, including a plain {@code SELECT}, fails with {@code current transaction is
 * aborted, commands ignored until end of transaction block} until {@code COMMIT} or {@code ROLLBACK}.
 * A genuine two-writer race therefore threw a second, uncaught exception out of {@code resolve()} --
 * confirmed empirically below as {@code JpaSystemException}, before the fix landed -- failing the
 * WHOLE import over one alias that only ever needed to be treated as a duplicate.
 *
 * <p>The fix replaces the insert-then-catch-then-requery pattern with
 * {@link MerchantAliasRepository#insertIfAbsent}, an {@code INSERT ... ON CONFLICT DO NOTHING}: the
 * database resolves a benign lost race atomically and silently, so no exception is ever raised for it
 * and the ambient transaction is never poisoned in the first place.
 *
 * <h2>Why this test does not use a {@code CyclicBarrier}</h2>
 *
 * <p>{@link MerchantLearningConfirmRaceIT} already answered this question for the same shape of bug
 * (BH-053) and left the answer in its own class comment: <i>"A {@code CyclicBarrier} on the two ...
 * calls would be racing the race -- it would pass or fail on scheduler luck."</i> This test uses the
 * same deterministic technique that class does: a {@code @SpyBean} hook that pauses the first caller
 * AFTER its insert has been issued (and is therefore genuinely holding the row lock) but BEFORE its
 * transaction commits, so the second caller's insert is REALLY blocked at the database on that lock,
 * not merely scheduled to run around the same time.
 *
 * <h2>What's pinned here</h2>
 *
 * <p>Two genuinely-concurrent first-time imports of the same brand-new merchant description, for the
 * same user, must both survive: neither caller's transaction may fail, and the alias table must end up
 * with exactly one row for that (user, normalized alias) pair -- never two, and never zero.
 */
class MerchantConcurrentAliasRaceIT extends AbstractIntegrationTest {

    @Autowired private MerchantNormalizationEngine engine;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    /** Real behaviour by default. Spied only to pause the first writer right after its insert is
     *  issued (so the row lock behind {@code UNIQUE(user_id, normalized_alias)} is held for real)
     *  and before its transaction commits -- see the class comment for why this replaces a
     *  {@code CyclicBarrier}.
     *
     *  <p>The stub below does NOT delegate to {@code invocation.callRealMethod()} -- the bean
     *  Spring wires here is a dynamic proxy over {@code SimpleJpaRepository}, not a concrete
     *  class, and Mockito cannot invoke a "real" method through a spy of an interface proxy
     *  ("Cannot call abstract real method on java object"). The stub instead issues the identical
     *  native SQL {@link MerchantAliasRepository#insertIfAbsent} runs, directly through the
     *  {@code EntityManager}, so the row lock it takes (and the blocking behaviour the second
     *  caller depends on) is the real thing, not a simulation. */
    @SpyBean private MerchantAliasRepository merchantAliasRepository;

    private static final String FIRST_THREAD = "alias-race-first";
    private static final String DESCRIPTION = "NOVELMERCHANT ALPHA STORE";

    private UUID newUser() {
        User user = new User();
        user.setEmail("alias-race-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Alias Race IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user).getId();
    }

    /**
     * Two callers, genuinely overlapping, both resolving the SAME never-before-seen description for
     * the SAME user -- exactly {@code addAlias}'s own doc comment's example ("one person confirming
     * two statements from two tabs"). Both must return a merchant without throwing, and the alias
     * table must end up with exactly one row for this (user, alias) pair.
     *
     * <p>The FIRST caller is parked with its alias insert already issued but its transaction still
     * open. The SECOND caller is started on its own thread specifically because it is expected to
     * block for real inside the database for as long as that lock is held -- calling it on the test
     * thread would hang the test on the same block. Releasing the first lets its transaction commit,
     * which is what unblocks the second's insert, which then genuinely collides with the
     * now-committed row.
     */
    @Test
    void twoCallersResolvingTheSameBrandNewDescriptionBothSurvive() throws Exception {
        UUID userId = newUser();
        String normalizedAlias = CategoryRules.normalize(DESCRIPTION);

        CountDownLatch firstHasInsertedButNotCommitted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        AtomicReference<Merchant> firstResult = new AtomicReference<>();
        AtomicReference<Merchant> secondResult = new AtomicReference<>();

        doAnswer(invocation -> {
            UUID merchantId = invocation.getArgument(0);
            UUID aliasUserId = invocation.getArgument(1);
            String aliasText = invocation.getArgument(2);
            // The exact SQL MerchantAliasRepository#insertIfAbsent runs -- kept identical rather
            // than delegated to, because Mockito cannot call a "real" method through a spy of an
            // interface-backed Spring Data proxy (see the field's own doc comment).
            int inserted = entityManager.createNativeQuery("""
                    INSERT INTO merchant_aliases (id, merchant_id, user_id, normalized_alias, created_at)
                    VALUES (gen_random_uuid(), :merchantId, :userId, :normalizedAlias, now())
                    ON CONFLICT (user_id, normalized_alias) DO NOTHING
                    """)
                    .setParameter("merchantId", merchantId)
                    .setParameter("userId", aliasUserId)
                    .setParameter("normalizedAlias", aliasText)
                    .executeUpdate();
            if (Thread.currentThread().getName().equals(FIRST_THREAD)) {
                firstHasInsertedButNotCommitted.countDown();
                assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return inserted;
        }).when(merchantAliasRepository).insertIfAbsent(any(), any(), anyString());

        Thread first = new Thread(() -> {
            try {
                firstResult.set(engine.resolve(userId, DESCRIPTION));
            } catch (Throwable t) {
                firstFailure.set(t);
            }
        }, FIRST_THREAD);
        first.start();

        assertThat(firstHasInsertedButNotCommitted.await(30, TimeUnit.SECONDS))
                .as("the first caller must actually be parked with its insert in place, uncommitted")
                .isTrue();

        Thread second = new Thread(() -> {
            try {
                secondResult.set(engine.resolve(userId, DESCRIPTION));
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        }, "alias-race-second");
        second.start();

        // No signal available from this side of the process for "the second caller is now blocked
        // inside the database" -- give it a moment to actually reach and issue its INSERT before
        // releasing the first caller, so the two genuinely overlap rather than running sequentially
        // by accident.
        Thread.sleep(500);

        releaseFirst.countDown();
        first.join(TimeUnit.SECONDS.toMillis(30));
        second.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(first.isAlive()).as("the first caller must have finished, not hung").isFalse();
        assertThat(second.isAlive()).as("the second caller must have finished, not hung").isFalse();

        assertThat(firstFailure.get()).as("neither caller's transaction may fail").isNull();
        assertThat(secondFailure.get())
                .as("the second caller lost the race and must survive it -- not crash the transaction "
                        + "it happens to be sharing with the rest of an import")
                .isNull();

        assertThat(firstResult.get()).isNotNull();
        assertThat(secondResult.get()).isNotNull();

        // findByUserIdAndNormalizedAlias returns Optional, so it would itself throw
        // IncorrectResultSizeDataAccessException if the unique constraint had somehow been
        // violated -- reaching the assertion below already rules out "two rows survived".
        Optional<MerchantAlias> aliasRow = merchantAliasRepository.findByUserIdAndNormalizedAlias(userId, normalizedAlias);
        assertThat(aliasRow)
                .as("exactly one alias row must survive -- the write that lost the race must not have "
                        + "vanished silently, and the unique constraint rules out two")
                .isPresent();

        // Both callers must be able to resolve the SAME description to the SAME merchant afterwards --
        // the alias row that survived is the source of truth, regardless of which caller's own
        // "candidate" merchant it ended up pointing at.
        Merchant resolvedAfterwards = engine.resolve(userId, DESCRIPTION);
        assertThat(resolvedAfterwards.getId()).isEqualTo(aliasRow.get().getMerchantId());
    }
}
