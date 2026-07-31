package com.finora.service;

import com.finora.dto.AdminDtos.PlatformStatsDto;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Aggregate, platform-wide counts for the admin Dashboard (frontend-admin/) -- deliberately just
 * COUNT queries against existing repositories, not a new reporting/analytics subsystem. Every
 * number here is a point-in-time snapshot computed on request, not cached or pre-aggregated:
 * these are simple indexed counts (user count, status count, date-threshold count), cheap enough
 * to run live at the traffic this platform sees today. Gated by PLATFORM_STATS_VIEW -- see
 * V24__admin_platform_stats_permission.sql.
 */
@Service
public class AdminStatsService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final StatementImportRepository statementImportRepository;

    public AdminStatsService(UserRepository userRepository, AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              StatementImportRepository statementImportRepository) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.statementImportRepository = statementImportRepository;
    }

    @Transactional(readOnly = true)
    public PlatformStatsDto overview() {
        Instant now = Instant.now();
        long totalUsers = userRepository.countByRoleNot("BOOTSTRAP_ADMIN");
        long suspendedUsers = userRepository.countByStatusAndRoleNot("SUSPENDED", "BOOTSTRAP_ADMIN");
        // Not userRepository.countByStatus("ACTIVE") separately -- status is a two-value column
        // (see the CHECK constraint in V23), so "active" is always exactly "everyone else."
        // Deriving it this way also means a future third status can't silently make these two
        // counts stop summing to totalUsers.
        long activeUsers = totalUsers - suspendedUsers;

        return new PlatformStatsDto(
                totalUsers,
                activeUsers,
                suspendedUsers,
                userRepository.countByCreatedAtAfter(now.minus(7, ChronoUnit.DAYS)),
                userRepository.countByCreatedAtAfter(now.minus(30, ChronoUnit.DAYS)),
                accountRepository.count(),
                transactionRepository.count(),
                statementImportRepository.count()
        );
    }
}
