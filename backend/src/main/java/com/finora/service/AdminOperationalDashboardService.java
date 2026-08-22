package com.finora.service;

import com.finora.dto.AdminDtos.OperationalDashboardDto;
import com.finora.dto.AdminDtos.NeedsAttentionDto;
import com.finora.dto.AdminDtos.PreviousDayDto;
import com.finora.dto.AdminDtos.ActivationFunnelDto;
import com.finora.dto.AdminDtos.ActivityTrendPointDto;
import com.finora.dto.AuditLogDto;
import com.finora.dto.HealthDtos.AlertDto;
import com.finora.dto.HealthDtos.PlatformHealthDto;
import com.finora.goals.GoalRepository;
import com.finora.health.HealthStatus;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * "Is Finora healthy?" -- the Operational Dashboard's single aggregation point, meant to be the
 * admin portal's actual home screen rather than a launchpad into other pages. Every count here is
 * a real, live query against existing tables (same "cheap live aggregate" discipline as
 * AdminStatsService) -- see OperationalDashboardDto's own doc comment for what's real vs. an
 * honest substitute (importsWithSkippedRowsToday instead of a fabricated "failed imports").
 * health/alerts are entirely delegated to AdminHealthRegistryService, which is itself built on
 * the extensible HealthProvider registry -- this class never hardcodes which systems exist.
 */
@Service
public class AdminOperationalDashboardService {

    private static final Logger log = LoggerFactory.getLogger(AdminOperationalDashboardService.class);

    private static final int RECENT_ACTIVITY_LIMIT = 8;
    private static final int INACTIVITY_WINDOW_DAYS = 7;
    private static final int ACTIVITY_TREND_DAYS = 7;

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final StatementImportRepository statementImportRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;
    private final AuditLogRepository auditLogRepository;
    private final AdminHealthRegistryService healthRegistryService;

    public AdminOperationalDashboardService(UserRepository userRepository, TransactionRepository transactionRepository,
                                             StatementImportRepository statementImportRepository,
                                             BudgetRepository budgetRepository, GoalRepository goalRepository,
                                             AuditLogRepository auditLogRepository,
                                             AdminHealthRegistryService healthRegistryService) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.statementImportRepository = statementImportRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
        this.auditLogRepository = auditLogRepository;
        this.healthRegistryService = healthRegistryService;
    }

    /** The zone every platform-wide "today"/"this month" tile is bucketed by. Configurable rather
     *  than hardcoded so a deployment serving a different market can say so, and defaulted to the
     *  one this platform actually targets rather than to UTC or to the container's arbitrary
     *  system zone. An unparseable value falls back to the default rather than failing the whole
     *  dashboard -- the same safe-fallback shape UserZone uses for a user's own zone. */
    @Value("${app.platform.reporting-zone:Asia/Kolkata}")
    private String reportingZoneId;

    private ZoneId platformReportingZone() {
        try {
            return ZoneId.of(reportingZoneId);
        } catch (Exception e) {
            log.warn("app.platform.reporting-zone is not a recognized zone id ({}) -- falling back "
                    + "to Asia/Kolkata for platform-wide 'today' tiles.", reportingZoneId);
            return ZoneId.of("Asia/Kolkata");
        }
    }

    @Transactional(readOnly = true)
    public OperationalDashboardDto overview() {
        // Midnight in the PLATFORM's reporting zone, not "last 24 rolling hours" -- so an admin
        // comparing this tile against yesterday's same tile at any time of day is comparing like
        // periods.
        //
        // This used to hardcode ZoneOffset.UTC, justified as matching "how every other 'today'
        // concept in this codebase treats calendar days". That justification stopped being true:
        // NetWorthService, BudgetService, GoalService, AnalyticsService and DashboardService were
        // each changed to resolve calendar boundaries against the USER's timezone through the
        // shared UserZone helper, precisely because a fixed zone produced wrong day and month
        // attribution -- AnalyticsService's own comment notes that "for the first 5.5 hours of an
        // IST month it names the previous month". A platform-wide aggregate genuinely has no one
        // user's zone to adopt, so a fixed zone is still right here; what was wrong is that the
        // fixed zone was not the deployment's. On an India-targeted platform, UTC midnight meant
        // "active users today" and "imports today" reset at 05:30 IST and the first five and a
        // half hours of each working day were attributed to yesterday.
        ZoneId zone = platformReportingZone();
        Instant startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant startOfYesterday = LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant();

        long totalUsers = userRepository.countByRoleNot("BOOTSTRAP_ADMIN");
        long activeUsersToday = auditLogRepository.countDistinctUsersByActionSince("USER_LOGIN", startOfToday);
        long transactionsToday = transactionRepository.countByCreatedAtAfter(startOfToday);
        long importsToday = statementImportRepository.countByImportedAtAfter(startOfToday);
        long importsWithSkippedRowsToday = statementImportRepository.countWithSkippedRowsAfter(startOfToday);

        // Same four "today" tiles, one calendar day earlier -- backs each tile's "vs yesterday"
        // delta. totalUsers deliberately has no sibling here; see PreviousDayDto's own doc comment.
        PreviousDayDto previousDay = new PreviousDayDto(
                auditLogRepository.countDistinctUsersByActionBetween("USER_LOGIN", startOfYesterday, startOfToday),
                transactionRepository.countByCreatedAtBetween(startOfYesterday, startOfToday),
                statementImportRepository.countByImportedAtBetween(startOfYesterday, startOfToday),
                statementImportRepository.countWithSkippedRowsBetween(startOfYesterday, startOfToday));

        PlatformHealthDto health = healthRegistryService.platformHealth();
        List<AlertDto> alerts = alertsFrom(health);

        NeedsAttentionDto needsAttention = new NeedsAttentionDto(
                importsWithSkippedRowsToday,
                userRepository.countByLockedUntilAfter(Instant.now()),
                transactionRepository.countByNeedsCategoryReviewTrue(),
                transactionRepository.countByIsDuplicateOfIsNotNull());

        // Insights row -- inverse of activeUsersToday's own query, same window this class already
        // uses for "today," just walked back INACTIVITY_WINDOW_DAYS instead of one.
        Instant inactivityCutoff = LocalDate.now(zone).minusDays(INACTIVITY_WINDOW_DAYS).atStartOfDay(zone).toInstant();
        long inactiveUsersLast7Days = userRepository.countWithNoAuditActionSince(
                "USER_LOGIN", inactivityCutoff, BootstrapService.BOOTSTRAP_IDENTIFIER);

        List<AuditLogDto> recentActivity = auditLogRepository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(0, RECENT_ACTIVITY_LIMIT, Sort.unsorted()))
                .getContent().stream()
                .map(l -> new AuditLogDto(l.getId(), l.getUserId(), l.getAction(), l.getEntityType(),
                        l.getEntityId(), l.getMetadata(), l.getRequestId(), l.getCreatedAt()))
                .toList();

        return new OperationalDashboardDto(totalUsers, activeUsersToday, transactionsToday, importsToday,
                importsWithSkippedRowsToday, inactiveUsersLast7Days, previousDay, needsAttention, health, alerts,
                recentActivity);
    }

    /** D-27 PR3-D. See {@link ActivationFunnelDto}'s own class doc for exactly what "reached" and
     *  "simple snapshot" mean here. countByRoleNot("BOOTSTRAP_ADMIN") reused as-is rather than a
     *  new query -- the same totalUsers definition {@link #overview()} already uses, so this
     *  funnel's own "signed up" figure agrees with the Operational Dashboard's totalUsers tile
     *  rather than quietly defining "total users" a second, different way. */
    @Transactional(readOnly = true)
    public ActivationFunnelDto activationFunnel() {
        return new ActivationFunnelDto(
                userRepository.countByRoleNot("BOOTSTRAP_ADMIN"),
                statementImportRepository.countDistinctUsersEverActivated(),
                budgetRepository.countDistinctUsersEverActivated(),
                goalRepository.countDistinctUsersEverActivated());
    }

    /**
     * Platform Activity chart: signups/imports/transactions for each of the last
     * {@value #ACTIVITY_TREND_DAYS} calendar days in the platform reporting zone, oldest first,
     * today included.
     *
     * Each day is its own bounded [start, nextDayStart) window, queried directly with the same
     * Between-style repository calls {@link #overview()} already uses for "yesterday" -- not one
     * bulk fetch bucketed in Java the way AdminLearningStatsService.trend() buckets by month.
     * That fetch-then-bucket shape exists there to walk a data-driven range and fill gaps between
     * whatever months actually have rows; this range is always exactly 7 fixed, known days, so
     * there is no gap-filling to get right or wrong -- the loop below always emits all 7 points
     * regardless of whether a given day had any activity.
     */
    @Transactional(readOnly = true)
    public List<ActivityTrendPointDto> activityTrend() {
        ZoneId zone = platformReportingZone();
        LocalDate today = LocalDate.now(zone);

        List<ActivityTrendPointDto> points = new ArrayList<>(ACTIVITY_TREND_DAYS);
        for (int daysAgo = ACTIVITY_TREND_DAYS - 1; daysAgo >= 0; daysAgo--) {
            LocalDate day = today.minusDays(daysAgo);
            Instant dayStart = day.atStartOfDay(zone).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();

            points.add(new ActivityTrendPointDto(
                    day,
                    userRepository.countByEmailNotAndCreatedAtBetween(
                            BootstrapService.BOOTSTRAP_IDENTIFIER, dayStart, dayEnd),
                    statementImportRepository.countByImportedAtBetween(dayStart, dayEnd),
                    transactionRepository.countByCreatedAtBetween(dayStart, dayEnd)));
        }
        return points;
    }

    /** Every non-UP provider becomes exactly one alert -- deliberately not a separate alerting
     *  engine with its own thresholds/rules, so there's one place (HealthProvider.check()) that
     *  decides what counts as a problem, not two that could disagree. */
    private List<AlertDto> alertsFrom(PlatformHealthDto health) {
        return health.providers().stream()
                .filter(p -> !p.status().equals(HealthStatus.UP.name()))
                .map(p -> new AlertDto(
                        p.status().equals(HealthStatus.DOWN.name()) ? "critical" : "warning",
                        p.name(),
                        p.detail()))
                .toList();
    }
}
