package com.finora.service;

import com.finora.dto.AdminDtos.PlatformStatsDto;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Previously had no coverage at all. Added specifically to lock in the bootstrap-account
 *  exclusion fix: totalUsers, suspendedUsers, and activeUsers must each exclude that system
 *  account -- and, separately (bug fix), to lock in that activeUsers is counted directly via
 *  status = 'ACTIVE' rather than derived as totalUsers - suspendedUsers. The derived form was
 *  correct only while status was a two-value column; V81 added DEACTIVATED (with more self-service
 *  statuses reserved for a later phase), and the subtraction silently started counting every
 *  non-suspended, non-active account as "active" -- exactly the gap
 *  overview_doesNotCountADeactivatedAccountAsActive below pins. */
class AdminStatsServiceTest {

    private UserRepository userRepository;
    private AdminStatsService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        StatementImportRepository statementImportRepository = mock(StatementImportRepository.class);
        service = new AdminStatsService(userRepository, accountRepository, transactionRepository, statementImportRepository);

        when(userRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(accountRepository.count()).thenReturn(0L);
        when(transactionRepository.count()).thenReturn(0L);
        when(statementImportRepository.count()).thenReturn(0L);
    }

    @Test
    void overview_excludesTheBootstrapAccount_fromTotalAndActiveUserCounts() {
        // 3 real users total (2 active, 1 suspended) plus the locked bootstrap account, which is
        // also status=SUSPENDED -- if it weren't excluded from every count, these numbers would be
        // off by one somewhere.
        when(userRepository.countByRoleNot("BOOTSTRAP_ADMIN")).thenReturn(3L);
        when(userRepository.countByStatusAndRoleNot("SUSPENDED", "BOOTSTRAP_ADMIN")).thenReturn(1L);
        when(userRepository.countByStatusAndRoleNot("ACTIVE", "BOOTSTRAP_ADMIN")).thenReturn(2L);

        PlatformStatsDto dto = service.overview();

        assertThat(dto.totalUsers()).isEqualTo(3L);
        assertThat(dto.suspendedUsers()).isEqualTo(1L);
        assertThat(dto.activeUsers()).isEqualTo(2L);
    }

    /** The regression test for the bug itself: a deactivated account is neither ACTIVE nor
     *  SUSPENDED, so a derived count (totalUsers - suspendedUsers) would wrongly include it. */
    @Test
    void overview_doesNotCountADeactivatedAccountAsActive() {
        // 3 users total: 1 active, 1 suspended, 1 deactivated.
        when(userRepository.countByRoleNot("BOOTSTRAP_ADMIN")).thenReturn(3L);
        when(userRepository.countByStatusAndRoleNot("SUSPENDED", "BOOTSTRAP_ADMIN")).thenReturn(1L);
        when(userRepository.countByStatusAndRoleNot("ACTIVE", "BOOTSTRAP_ADMIN")).thenReturn(1L);

        PlatformStatsDto dto = service.overview();

        assertThat(dto.totalUsers()).isEqualTo(3L);
        // The old totalUsers - suspendedUsers derivation would have reported 2 here (wrongly
        // counting the deactivated account as active).
        assertThat(dto.activeUsers()).isEqualTo(1L);
    }
}
