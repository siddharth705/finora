package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.NetWorthSnapshot;
import com.finora.entity.User;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NetWorthSnapshotRepository#upsertForToday} against a real Postgres, not a mock.
 *
 * <p>Found and fixed while auditing for the same class of bug as
 * {@code MerchantNormalizationEngine.addAlias}: {@code NetWorthService.saveSnapshotForToday} used
 * to be a find-then-save guarded by {@code catch (DataIntegrityViolationException)} that re-found
 * and re-saved the winner's row on a lost race -- the identical "write, catch, re-query inside the
 * same call" shape that turned out to poison an ambient transaction in {@code addAlias}. It never
 * actually did so HERE, purely because {@code saveSnapshotForToday} carries no {@code
 * @Transactional} and its only caller doesn't either, so relying on it staying safe meant relying on
 * nobody ever adding one. This is now an {@code INSERT ... ON CONFLICT DO UPDATE}, the same shape
 * {@link com.finora.repository.RegisteredLayoutRepository#observe} uses and {@code LayoutRegistryIT}
 * proves against real Postgres -- what's asserted here is exactly that: the ON CONFLICT clause
 * actually behaves as an overwrite, and concurrent callers never see an exception, regardless of
 * what transaction boundary wraps them.
 */
class NetWorthSnapshotUpsertIT extends AbstractIntegrationTest {

    @Autowired private NetWorthSnapshotRepository snapshotRepository;
    @Autowired private UserRepository userRepository;

    private UUID newUser() {
        User user = new User();
        user.setEmail("networth-upsert-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Net Worth Upsert IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user).getId();
    }

    private NetWorthSnapshot reload(UUID userId, LocalDate date) {
        return snapshotRepository.findByUserIdAndSnapshotDate(userId, date).orElseThrow();
    }

    @Test
    void aFirstSaveForTheDayCreatesOneRow() {
        UUID userId = newUser();
        LocalDate today = LocalDate.now();

        snapshotRepository.upsertForToday(userId, today,
                new BigDecimal("1000.00"), new BigDecimal("200.00"), new BigDecimal("800.00"));

        NetWorthSnapshot snap = reload(userId, today);
        assertThat(snap.getTotalAssets()).isEqualByComparingTo("1000.00");
        assertThat(snap.getTotalLiabilities()).isEqualByComparingTo("200.00");
        assertThat(snap.getNetWorth()).isEqualByComparingTo("800.00");
    }

    @Test
    void aSecondSaveTheSameDayOverwritesTheFigures_ratherThanCreatingASecondRow() {
        UUID userId = newUser();
        LocalDate today = LocalDate.now();
        snapshotRepository.upsertForToday(userId, today,
                new BigDecimal("1000.00"), new BigDecimal("200.00"), new BigDecimal("800.00"));

        snapshotRepository.upsertForToday(userId, today,
                new BigDecimal("1500.00"), new BigDecimal("300.00"), new BigDecimal("1200.00"));

        List<NetWorthSnapshot> rows = snapshotRepository.findByUserIdOrderBySnapshotDateAsc(userId);
        assertThat(rows).as("same-day snapshots overwrite; they never accumulate").hasSize(1);
        assertThat(rows.get(0).getTotalAssets()).isEqualByComparingTo("1500.00");
        assertThat(rows.get(0).getNetWorth()).isEqualByComparingTo("1200.00");
    }

    @Test
    void concurrentSavesForTheSameDayProduceOneRowAndNeitherCallerSeesAnException() {
        // The property the fix exists for. Read-then-insert would have both threads read "absent"
        // and one of them hit net_worth_snapshots(user_id, snapshot_date) UNIQUE -- which, before
        // this fix, meant a re-query that (if this ran inside an ambient transaction, as
        // MerchantNormalizationEngine.addAlias's did) could itself fail against an already-poisoned
        // transaction. ON CONFLICT DO UPDATE removes the exception entirely rather than recovering
        // from it after the fact.
        UUID userId = newUser();
        LocalDate today = LocalDate.now();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> saves = java.util.stream.IntStream.range(0, threads)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        snapshotRepository.upsertForToday(userId, today,
                                new BigDecimal(1000 + i), new BigDecimal(100 + i), new BigDecimal(900 + i));
                        return null;
                    })
                    .toList();
            pool.invokeAll(saves).forEach(future -> {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("A concurrent save threw instead of overwriting: " + e, e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }

        assertThat(snapshotRepository.findByUserIdOrderBySnapshotDateAsc(userId))
                .as("six concurrent writers for the same user+day still leave exactly one row")
                .hasSize(1);
    }
}
