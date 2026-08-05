package com.finora.service;

import com.finora.dto.StatementImportDto.AccountGroup;
import com.finora.entity.Account;
import com.finora.imports.ImportService;
import com.finora.entity.StatementImport;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Statement History's "deleted account still shows up" behavior (see
 * StatementImportService.listGroupedByAccount): an account deleted after a statement was
 * imported into it used to stay visible in that page forever, with no name to show for it
 * ("Deleted account", indefinitely). This covers the fix — visible for a 7-day grace period
 * from the deletion date, then dropped from the response entirely.
 */
class StatementImportServiceRetentionTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final StatementImportRepository statementImportRepository = mock(StatementImportRepository.class);
    // listGroupedByAccount() resolves each account's bank via this to build its AccountGroup --
    // stubbed to fall back through to the real static registry, same as AccountServiceTest, so
    // this suite's retention-window assertions don't have to know anything about custom banks.
    private final BankManagementService bankManagementService = mock(BankManagementService.class);
    private final StatementImportService service = new StatementImportService(
            statementImportRepository, accountRepository, mock(CategoryRepository.class),
            mock(TransactionRepository.class), mock(ReconciliationService.class), mock(RecurringService.class),
            mock(ImportService.class), mock(AuditService.class), bankManagementService, new com.finora.imports.storage.StatementContentService(java.util.Optional.empty(), "", ""));

    {
        when(bankManagementService.resolve(any())).thenAnswer(invocation ->
                com.finora.accounts.AccountDto.BankDto.from(com.finora.util.BankRegistry.get(invocation.getArgument(0))));
    }

    private final UUID userId = UUID.randomUUID();

    private Account account(UUID id, String name, Instant deletedAt) {
        Account a = new Account();
        ReflectionTestUtils.setField(a, "id", id);
        a.setUserId(userId);
        a.setName(name);
        a.setAccountType(Account.Type.SAVINGS);
        ReflectionTestUtils.setField(a, "deletedAt", deletedAt);
        return a;
    }

    private StatementImport statement(UUID accountId) {
        StatementImport s = new StatementImport();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setUserId(userId);
        s.setAccountId(accountId);
        s.setFileName("statement.csv");
        return s;
    }

    @Test
    void listGroupedByAccount_showsActiveAccount_andDeletedAccountWithinRetentionWindow_butDropsOlderDeletion() {
        UUID activeId = UUID.randomUUID();
        UUID recentlyDeletedId = UUID.randomUUID();
        UUID longDeletedId = UUID.randomUUID();

        when(accountRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(
                account(activeId, "SBI Savings", null),
                account(recentlyDeletedId, "PNB Savings", Instant.now().minus(3, ChronoUnit.DAYS)),
                account(longDeletedId, "Old Wallet", Instant.now().minus(10, ChronoUnit.DAYS))
        ));
        when(statementImportRepository.findByUserIdOrderByImportedAtDesc(userId)).thenReturn(List.of(
                statement(activeId), statement(recentlyDeletedId), statement(longDeletedId)
        ));

        List<AccountGroup> groups = service.listGroupedByAccount(userId);

        assertThat(groups).hasSize(2);
        assertThat(groups).extracting(AccountGroup::accountId).containsExactlyInAnyOrder(activeId, recentlyDeletedId);

        AccountGroup active = groups.stream().filter(g -> g.accountId().equals(activeId)).findFirst().orElseThrow();
        assertThat(active.deleted()).isFalse();
        assertThat(active.accountName()).isEqualTo("SBI Savings");

        AccountGroup recentlyDeleted = groups.stream().filter(g -> g.accountId().equals(recentlyDeletedId)).findFirst().orElseThrow();
        assertThat(recentlyDeleted.deleted()).isTrue();
        assertThat(recentlyDeleted.accountName()).isEqualTo("PNB Savings");
        assertThat(recentlyDeleted.deletedAt()).isNotNull();
    }

    @Test
    void listGroupedByAccount_excludesAccountDeletedMoreThanSevenDaysAgo() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(
                account(accountId, "Old Card", Instant.now().minus(8, ChronoUnit.DAYS))
        ));
        when(statementImportRepository.findByUserIdOrderByImportedAtDesc(userId)).thenReturn(List.of(
                statement(accountId)
        ));

        List<AccountGroup> groups = service.listGroupedByAccount(userId);

        assertThat(groups).isEmpty();
    }
}
