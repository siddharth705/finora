package com.finora.service;

import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Track C/C8: an automatic daily net-worth snapshot, so a user's history doesn't have gaps just
 * because nobody remembered to press "Save today's snapshot" (the {@code NetWorthController}
 * endpoint that button calls stays exactly as it is, as a same-day manual override -- {@link
 * NetWorthService#saveSnapshotForToday} and this sweep call the identical method, and {@code
 * NetWorthSnapshotRepository#upsertForToday}'s {@code ON CONFLICT DO UPDATE} already makes calling
 * it twice for the same user on the same day free, whichever of the two calls it).
 *
 * <p>Deliberately NOT one global "today" for the whole sweep. {@code saveSnapshotForToday}
 * resolves "today" per user against {@code User.timezone} ({@code UserZone.of}) -- the same
 * bug fix already applied to the manual save path, because a single UTC (or server-local) cutover
 * would stamp the wrong calendar date on every user meaningfully east or west of wherever that
 * clock sits. Running this sweep every {@code interval-ms} rather than once at a fixed clock time
 * means every user's own local midnight falls inside some run, without this class having to reason
 * about timezones itself at all -- that reasoning stays exactly where it already lived.
 *
 * <p>Flag-gated for the same reason every other sweep in this codebase is: a background thread
 * writing {@code net_worth_snapshots} mid-test would be the cross-test pollution BH-058 was about.
 * {@code application-test.yml} turns it off; tests call {@link #sweep()} directly.
 */
@Service
public class NetWorthSnapshotSweepService {

    private static final Logger log = LoggerFactory.getLogger(NetWorthSnapshotSweepService.class);

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final NetWorthService netWorthService;

    @Value("${app.net-worth-snapshot.sweep.enabled:true}")
    private boolean sweepEnabled;

    public NetWorthSnapshotSweepService(AccountRepository accountRepository, UserRepository userRepository,
                                         NetWorthService netWorthService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.netWorthService = netWorthService;
    }

    /**
     * {@code fixedDelay}, not {@code fixedRate}: the next sweep starts after the previous one
     * finishes, matching {@code AccountPurgeSweepService}'s own reasoning -- a slow pass (many
     * users, each a handful of queries) cannot pile up overlapping runs.
     */
    @Scheduled(fixedDelayString = "${app.net-worth-snapshot.sweep.interval-ms:14400000}",
            initialDelayString = "${app.net-worth-snapshot.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        Result result = sweep();
        if (result.failed() > 0) {
            log.info("Net worth snapshot sweep: {} saved, {} skipped, {} failed.",
                    result.saved(), result.skipped(), result.failed());
        }
    }

    /**
     * One sweep pass: every user who has at least one account gets today's (their today's)
     * net worth snapshotted. One user's failure is caught here and does not stop the batch --
     * that user is simply retried whole on the next run, same as {@code AccountPurgeSweepService}.
     *
     * @return how many snapshots were saved, skipped (user not ACTIVE), or failed
     */
    public Result sweep() {
        List<UUID> candidates = accountRepository.findDistinctUserIds();

        int saved = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID userId : candidates) {
            User user = userRepository.findById(userId).orElse(null);
            // Only ACTIVE users -- a suspended, deactivated, pending-deletion, or already-deleted
            // account has no business accruing a new daily data point.
            if (user == null || !User.STATUS_ACTIVE.equals(user.getStatus())) {
                skipped++;
                continue;
            }
            try {
                netWorthService.saveSnapshotForToday(userId);
                saved++;
            } catch (Exception e) {
                failed++;
                log.error("Net worth snapshot failed for user {}: {}", userId, e.getMessage(), e);
            }
        }
        return new Result(saved, skipped, failed);
    }

    public record Result(int saved, int skipped, int failed) {}
}
