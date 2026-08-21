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
        // Bug fix: this used to derive activeUsers as totalUsers - suspendedUsers, on the reasoning
        // that status was a two-value column (V23's original CHECK constraint) so "active" was
        // always exactly "everyone else." V87 widened status to include DEACTIVATED (and reserves
        // room for more self-service states in a later phase) -- the subtraction silently started
        // counting deactivated accounts as active, with no test catching it (AdminStatsServiceTest
        // only ever exercised the ACTIVE/SUSPENDED world). Counting ACTIVE explicitly is immune to
        // however many more statuses this column grows to hold.
        long activeUsers = userRepository.countByStatusAndRoleNot("ACTIVE", "BOOTSTRAP_ADMIN");

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
