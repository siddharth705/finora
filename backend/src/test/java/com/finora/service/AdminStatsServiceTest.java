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
 *  exclusion fix: totalUsers and suspendedUsers must exclude that system account *together*,
 *  since activeUsers is derived as totalUsers - suspendedUsers (see overview()'s own doc
 *  comment) -- excluding it from only one side would make activeUsers go negative by one the
 *  moment the bootstrap account is locked (status=SUSPENDED) after setup completes. */
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
        // also status=SUSPENDED -- if it weren't excluded from suspendedUsers too, activeUsers
        // would come out to 3 - 2 = 1 instead of the correct 2.
        when(userRepository.countByRoleNot("BOOTSTRAP_ADMIN")).thenReturn(3L);
        when(userRepository.countByStatusAndRoleNot("SUSPENDED", "BOOTSTRAP_ADMIN")).thenReturn(1L);

        PlatformStatsDto dto = service.overview();

        assertThat(dto.totalUsers()).isEqualTo(3L);
        assertThat(dto.suspendedUsers()).isEqualTo(1L);
        assertThat(dto.activeUsers()).isEqualTo(2L);
    }
}
