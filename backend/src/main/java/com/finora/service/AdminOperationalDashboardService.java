package com.finora.service;

import com.finora.dto.AdminDtos.OperationalDashboardDto;
import com.finora.dto.AdminDtos.NeedsAttentionDto;
import com.finora.dto.AuditLogDto;
import com.finora.dto.HealthDtos.AlertDto;
import com.finora.dto.HealthDtos.PlatformHealthDto;
import com.finora.health.HealthStatus;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    private static final int RECENT_ACTIVITY_LIMIT = 8;

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final StatementImportRepository statementImportRepository;
    private final AuditLogRepository auditLogRepository;
    private final AdminHealthRegistryService healthRegistryService;

    public AdminOperationalDashboardService(UserRepository userRepository, TransactionRepository transactionRepository,
                                             StatementImportRepository statementImportRepository,
                                             AuditLogRepository auditLogRepository,
                                             AdminHealthRegistryService healthRegistryService) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.statementImportRepository = statementImportRepository;
        this.auditLogRepository = auditLogRepository;
        this.healthRegistryService = healthRegistryService;
    }

    @Transactional(readOnly = true)
    public OperationalDashboardDto overview() {
        // Midnight UTC, not "last 24 rolling hours" -- matches how every other "today" concept
        // in this codebase (Transaction.txnDate-based monthly bucketing, YearMonth.now()) treats
        // calendar days, so an admin comparing this tile against yesterday's same tile at any
        // time of day is comparing like periods.
        Instant startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();

        long totalUsers = userRepository.countByRoleNot("BOOTSTRAP_ADMIN");
        long activeUsersToday = auditLogRepository.countDistinctUsersByActionSince("USER_LOGIN", startOfToday);
        long transactionsToday = transactionRepository.countByCreatedAtAfter(startOfToday);
        long importsToday = statementImportRepository.countByImportedAtAfter(startOfToday);
        long importsWithSkippedRowsToday = statementImportRepository.countWithSkippedRowsAfter(startOfToday);

        PlatformHealthDto health = healthRegistryService.platformHealth();
        List<AlertDto> alerts = alertsFrom(health);

        NeedsAttentionDto needsAttention = new NeedsAttentionDto(
                importsWithSkippedRowsToday,
                userRepository.countByLockedUntilAfter(Instant.now()),
                transactionRepository.countByNeedsCategoryReviewTrue(),
                transactionRepository.countByIsDuplicateOfIsNotNull());

        List<AuditLogDto> recentActivity = auditLogRepository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(0, RECENT_ACTIVITY_LIMIT, Sort.unsorted()))
                .getContent().stream()
                .map(l -> new AuditLogDto(l.getId(), l.getUserId(), l.getAction(), l.getEntityType(),
                        l.getEntityId(), l.getMetadata(), l.getRequestId(), l.getCreatedAt()))
                .toList();

        return new OperationalDashboardDto(totalUsers, activeUsersToday, transactionsToday, importsToday,
                importsWithSkippedRowsToday, needsAttention, health, alerts, recentActivity);
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
