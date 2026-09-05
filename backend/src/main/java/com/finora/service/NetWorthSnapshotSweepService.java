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
 * endpoint that button calls, {@link NetWorthService#saveSnapshotForToday}, stays exactly as it
 * is, as a same-day manual override -- this sweep calls {@link
 * NetWorthService#snapshotForTodayOnly} instead, the same computation and upsert without
 * {@code saveSnapshotForToday}'s closing read-back this sweep has no use for, but {@code
 * NetWorthSnapshotRepository#upsertForToday}'s {@code ON CONFLICT DO UPDATE} makes either path
 * calling for the same user on the same day free regardless of which one gets there first).
 *
 * <p>Deliberately NOT one global "today" for the whole sweep. Each user's "today" resolves
 * against their own {@code User.timezone} ({@code UserZone.of}) -- the same bug fix already
 * applied to the manual save path, because a single UTC (or server-local) cutover would stamp the
 * wrong calendar date on every user meaningfully east or west of wherever that clock sits.
 * Running this sweep every {@code interval-ms} rather than once at a fixed clock time means every
 * user's own local midnight falls inside some run, without this class having to reason about
 * timezones itself at all -- that reasoning stays exactly where it already lived.
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
        // Unconditional: saved/skipped are the routine, all-succeeding-day numbers an operator
        // needs to confirm the sweep is actually running and how many users it covers -- gating
        // this on failed > 0 (as originally written) meant it logged nothing at all on every
        // normal day, which is most days.
        log.info("Net worth snapshot sweep: {} saved, {} skipped, {} failed.",
                result.saved(), result.skipped(), result.failed());
    }

    /**
     * One sweep pass: every ACTIVE user who has at least one account gets today's (their
     * today's) net worth snapshotted. One user's failure is caught here and does not stop the
     * batch -- that user is simply retried whole on the next run, same as {@code
     * AccountPurgeSweepService}.
     *
     * <p>The ACTIVE filter runs at the query layer ({@link UserRepository#findByIdInAndStatus}),
     * not as a per-candidate check in this loop -- the same query also returns each user's
     * timezone, so a non-active candidate never costs a fetch, and an active one costs exactly
     * the one batch query plus its own {@link NetWorthService#snapshotForTodayOnly} call (one
     * accounts fetch, one upsert) rather than the multiple duplicate/discarded queries routing
     * through {@code saveSnapshotForToday} would have cost per user, every run, forever.
     *
     * @return how many snapshots were saved, skipped (user not ACTIVE), or failed
     */
    public Result sweep() {
        List<UUID> candidates = accountRepository.findDistinctUserIds();
        List<User> activeUsers = userRepository.findByIdInAndStatus(candidates, User.STATUS_ACTIVE);
        int skipped = candidates.size() - activeUsers.size();

        int saved = 0;
        int failed = 0;
        for (User user : activeUsers) {
            try {
                netWorthService.snapshotForTodayOnly(user.getId(), user.getTimezone());
                saved++;
            } catch (Exception e) {
                failed++;
                log.error("Net worth snapshot failed for user {}: {}", user.getId(), e.getMessage(), e);
            }
        }
        return new Result(saved, skipped, failed);
    }

    public record Result(int saved, int skipped, int failed) {}
}
