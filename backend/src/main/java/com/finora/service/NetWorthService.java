package com.finora.service;

import com.finora.accounts.AccountBalanceConvention;
import com.finora.dto.NetWorthDto;
import com.finora.entity.Account;
import com.finora.entity.NetWorthSnapshot;
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
     *  underlying problem -- an invalid/malformed value falls back rather than 500ing). */
    public NetWorthDto saveSnapshotForToday(UUID userId) {
        List<Account> accounts = accountRepository.findByUserId(userId);
        BigDecimal liquid = sum(accounts, Account.Type.SAVINGS).add(sum(accounts, Account.Type.WALLET));
        BigDecimal investments = sum(accounts, Account.Type.INVESTMENT);
        BigDecimal liabilities = sum(accounts, Account.Type.CREDIT_CARD);
        BigDecimal totalAssets = liquid.add(investments);
        BigDecimal netWorth = netWorthOf(accounts);
        LocalDate today = LocalDate.now(safeZoneId(userRepository.findById(userId).map(User::getTimezone).orElse(null)));

        NetWorthSnapshot snap = snapshotRepository.findByUserIdAndSnapshotDate(userId, today)
                .orElseGet(NetWorthSnapshot::new);
        snap.setUserId(userId);
        snap.setSnapshotDate(today);
        snap.setTotalAssets(totalAssets);
        snap.setTotalLiabilities(liabilities);
        snap.setNetWorth(netWorth);
        try {
            snapshotRepository.save(snap);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Bug fix: the find-then-save above is a genuine check-then-act race -- two
            // concurrent calls for the same user+day (a double-click on "Save today's snapshot,"
            // or a retried request) could both find nothing, both try to INSERT a new row, and
            // the net_worth_snapshots(user_id, snapshot_date) UNIQUE constraint (V1 migration)
            // would let exactly one of those inserts through and throw this exception for the
            // other -- correctly preventing duplicate rows, but as an unhandled 500 rather than
            // the "same-day snapshots overwrite" behavior this method's own doc comment promises.
            // The loser here just means someone else's concurrent request won the race and
            // already created today's row -- re-fetching and updating THAT row instead achieves
            // the same overwrite semantics the non-racing path already has.
            snap = snapshotRepository.findByUserIdAndSnapshotDate(userId, today)
                    .orElseThrow(() -> e); // genuinely shouldn't happen -- rethrow the original rather than swallow a real problem
            snap.setTotalAssets(totalAssets);
            snap.setTotalLiabilities(liabilities);
            snap.setNetWorth(netWorth);
            snapshotRepository.save(snap);
        }

        return current(userId);
    }

    /** Same defensive fallback DashboardService.safeZoneId() uses -- timezone has no format
     *  validation on the settings-update path (UserSettingsDto.UpdateRequest accepts any
     *  string), so this falls back to the column's own default (V11 migration) rather than
     *  letting a malformed value throw an uncaught DateTimeException here. */
    private ZoneId safeZoneId(String timezone) {
        if (timezone == null) return ZoneId.of("Asia/Kolkata");
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
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
