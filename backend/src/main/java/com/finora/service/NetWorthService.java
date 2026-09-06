package com.finora.service;

import com.finora.accounts.AccountBalanceConvention;
import com.finora.dto.NetWorthDto;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class NetWorthService {

    private final AccountRepository accountRepository;
    private final NetWorthSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;

    public NetWorthService(AccountRepository accountRepository, NetWorthSnapshotRepository snapshotRepository,
                            UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public NetWorthDto current(UUID userId) {
        List<Account> accounts = accountRepository.findByUserId(userId);
        BigDecimal liquid = sum(accounts, Account.Type.SAVINGS).add(sum(accounts, Account.Type.WALLET));
        BigDecimal investments = sum(accounts, Account.Type.INVESTMENT);
        BigDecimal liabilities = sum(accounts, Account.Type.CREDIT_CARD);
        BigDecimal totalAssets = liquid.add(investments);
        BigDecimal netWorth = netWorthOf(accounts);

        List<NetWorthDto.SnapshotPoint> history = snapshotRepository.findByUserIdOrderBySnapshotDateAsc(userId)
                .stream().map(s -> new NetWorthDto.SnapshotPoint(s.getSnapshotDate(), s.getNetWorth())).toList();

        return new NetWorthDto(totalAssets, liabilities, netWorth, history);
    }

    /** Called from the Investments page's "Save today's snapshot" button — same-day snapshots overwrite.
     *  Bug fix: this used to call the bare LocalDate.now(), which resolves to the server's JVM
     *  default timezone, not the user's own -- a user meaningfully east or west of wherever the
     *  server happens to run could get yesterday's or tomorrow's date stamped on a snapshot they
     *  clicked "save" on today, from their own point of view. Now resolves against
     *  User.timezone (same safe-fallback pattern DashboardService already uses for the same
     *  underlying problem -- an invalid/malformed value falls back rather than 500ing).
     *
     *  <p>Bug fix, second (found while auditing for the same class of bug as
     *  {@code MerchantNormalizationEngine.addAlias}). This used to be
     *  {@code findByUserIdAndSnapshotDate().orElseGet(new)} then {@code save()}, guarded by a
     *  {@code catch (DataIntegrityViolationException)} that re-found and re-saved the winner's row
     *  on a lost race -- the exact "insert, catch, re-query inside the same call" shape that turned
     *  out to poison an ambient transaction in {@code addAlias}. It never actually did so HERE,
     *  purely because this method carries no {@code @Transactional}, and at the time neither did
     *  its only caller ({@code NetWorthController}), so every repository call already ran in its
     *  own self-contained transaction -- the same accident of omission {@code BootstrapService.run}
     *  documents relying on deliberately. Relying on "nobody has added @Transactional yet" is not a
     *  guarantee, so this is now {@link NetWorthSnapshotRepository#upsertForToday}, an
     *  {@code INSERT ... ON CONFLICT DO UPDATE}: the database resolves the race atomically, so
     *  there is no exception to catch and no ambient transaction that could be poisoned regardless
     *  of what this method or ANY caller are annotated with -- {@code
     *  NetWorthSnapshotSweepService.sweep()} is now a second caller, from a background thread with
     *  no ambient transaction of its own either, and the guarantee above holds for it exactly the
     *  same way. */
    public NetWorthDto saveSnapshotForToday(UUID userId) {
        String timezone = userRepository.findById(userId).map(User::getTimezone).orElse(null);
        snapshotForTodayOnly(userId, timezone);
        return current(userId);
    }

    /** Same computation and upsert as {@link #saveSnapshotForToday}, without the closing {@link
     *  #current} read-back. For {@code NetWorthSnapshotSweepService}, which already has each
     *  user's timezone from its own batch query and has no use for the returned {@code
     *  NetWorthDto} -- routing it through {@code saveSnapshotForToday} instead would cost every
     *  swept user a duplicate {@code accountRepository.findByUserId} call and a fetch of their
     *  entire, unbounded snapshot history just to discard both. */
    public void snapshotForTodayOnly(UUID userId, String timezone) {
        List<Account> accounts = accountRepository.findByUserId(userId);
        BigDecimal liquid = sum(accounts, Account.Type.SAVINGS).add(sum(accounts, Account.Type.WALLET));
        BigDecimal investments = sum(accounts, Account.Type.INVESTMENT);
        BigDecimal liabilities = sum(accounts, Account.Type.CREDIT_CARD);
        BigDecimal totalAssets = liquid.add(investments);
        BigDecimal netWorth = netWorthOf(accounts);
        LocalDate today = LocalDate.now(safeZoneId(timezone));

        snapshotRepository.upsertForToday(userId, today, totalAssets, liabilities, netWorth);
    }

    /** Delegates to {@link com.finora.util.UserZone} -- this was one of four hand-copied
     *  {@code safeZoneId} implementations. See that class's doc comment for why copying it per
     *  service is what let AnalyticsService end up as the one that never got a copy. */
    private ZoneId safeZoneId(String timezone) {
        return com.finora.util.UserZone.of(timezone);
    }

    /**
     * Net worth as the sum of every account's own contribution, rather than
     * {@code assets.subtract(liabilities)} written out by hand here and in two other services.
     *
     * The two agree for every non-negative liability balance, so this is not a behaviour change for
     * ordinary data -- it is the removal of a rule that was duplicated four ways (see
     * {@link AccountBalanceConvention}). It also stays correct for a credit balance on a card,
     * which the subtraction form only handles by coincidence.
     */
    private BigDecimal netWorthOf(List<Account> accounts) {
        return accounts.stream()
                .map(a -> AccountBalanceConvention.netWorthContribution(a.getAccountType(), a.getBalance()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sum(List<Account> accounts, Account.Type type) {
        return accounts.stream().filter(a -> a.getAccountType() == type)
                .map(Account::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
