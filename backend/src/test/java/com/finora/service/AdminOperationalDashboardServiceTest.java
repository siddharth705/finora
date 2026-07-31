package com.finora.service;

import com.finora.dto.AdminDtos.OperationalDashboardDto;
import com.finora.dto.HealthDtos.PlatformHealthDto;
import com.finora.dto.HealthDtos.ProviderStatusDto;
import com.finora.entity.AuditLog;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Mocked-repository unit test, same pattern as WorkspaceDashboardServiceTest. Proves the
 *  alerts panel is derived purely from the health registry (not a second, independent alerting
 *  path) and that recent activity gets mapped into DTOs correctly. */
class AdminOperationalDashboardServiceTest {

    private UserRepository userRepository;
    private TransactionRepository transactionRepository;
    private StatementImportRepository statementImportRepository;
    private AuditLogRepository auditLogRepository;
    private AdminHealthRegistryService healthRegistryService;
    private AdminOperationalDashboardService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        healthRegistryService = mock(AdminHealthRegistryService.class);
        service = new AdminOperationalDashboardService(userRepository, transactionRepository,
                statementImportRepository, auditLogRepository, healthRegistryService);

        when(userRepository.countByRoleNot("BOOTSTRAP_ADMIN")).thenReturn(0L);
        when(auditLogRepository.countDistinctUsersByActionSince(any(), any())).thenReturn(0L);
        when(transactionRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(statementImportRepository.countByImportedAtAfter(any())).thenReturn(0L);
        when(statementImportRepository.countWithSkippedRowsAfter(any())).thenReturn(0L);
        when(userRepository.countByLockedUntilAfter(any())).thenReturn(0L);
        when(transactionRepository.countByNeedsCategoryReviewTrue()).thenReturn(0L);
        when(transactionRepository.countByIsDuplicateOfIsNotNull()).thenReturn(0L);
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(Page.empty());
    }

    @Test
    void overview_populatesNeedsAttentionFromRealPlatformWideCounts() {
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", List.of()));
        when(statementImportRepository.countWithSkippedRowsAfter(any())).thenReturn(3L);
        when(userRepository.countByLockedUntilAfter(any())).thenReturn(2L);
        when(transactionRepository.countByNeedsCategoryReviewTrue()).thenReturn(14L);
        when(transactionRepository.countByIsDuplicateOfIsNotNull()).thenReturn(5L);

        OperationalDashboardDto dto = service.overview();

        assertThat(dto.needsAttention().importsWithSkippedRowsToday()).isEqualTo(3L);
        assertThat(dto.needsAttention().lockedAccounts()).isEqualTo(2L);
        assertThat(dto.needsAttention().transactionsNeedingCategoryReview()).isEqualTo(14L);
        assertThat(dto.needsAttention().transactionsFlaggedAsDuplicates()).isEqualTo(5L);
    }

    @Test
    void overview_producesNoAlerts_whenEveryHealthProviderIsUp() {
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", List.of(
                new ProviderStatusDto("Database", "Platform", "UP", "all good")
        )));

        OperationalDashboardDto dto = service.overview();

        assertThat(dto.alerts()).isEmpty();
    }

    @Test
    void overview_turnsEveryNonUpProviderIntoExactlyOneAlert() {
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("DOWN", List.of(
                new ProviderStatusDto("Database", "Platform", "UP", "all good"),
                new ProviderStatusDto("Financial Intelligence Engine", "Financial Intelligence", "DOWN", "2 dangling pointers"),
                new ProviderStatusDto("Statement Import Pipeline", "Financial Intelligence", "DEGRADED", "high skip rate")
        )));

        OperationalDashboardDto dto = service.overview();

        assertThat(dto.alerts()).hasSize(2);
        assertThat(dto.alerts()).anySatisfy(a -> {
            assertThat(a.title()).isEqualTo("Financial Intelligence Engine");
            assertThat(a.severity()).isEqualTo("critical");
        });
        assertThat(dto.alerts()).anySatisfy(a -> {
            assertThat(a.title()).isEqualTo("Statement Import Pipeline");
            assertThat(a.severity()).isEqualTo("warning");
        });
    }

    @Test
    void overview_mapsRecentAuditLogEntriesIntoDtos() {
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", List.of()));
        AuditLog log = new AuditLog();
        ReflectionTestUtils.setField(log, "id", UUID.randomUUID());
        log.setUserId(UUID.randomUUID());
        log.setAction("USER_LOGIN");
        log.setEntityType("User");
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log)));

        OperationalDashboardDto dto = service.overview();

        assertThat(dto.recentActivity()).hasSize(1);
        assertThat(dto.recentActivity().get(0).action()).isEqualTo("USER_LOGIN");
    }
}
