package com.finora.imports;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.integrations.setu.AccountAggregatorLinkRepository;
import com.finora.integrations.setu.AccountAggregatorLinkStatus;
import com.finora.repository.AccountRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused on the one behavior this task adds -- resolveTargetAccount's full existing behavior
 * (product-identity matching, new-account creation) is already covered by ImportService's other manual-import tests and is
 * untouched here.
 */
class ImportServiceAccountAggregatorBlockTest {

    @Test
    void refusesAnExistingAccountWhoseAaLinkIsActive() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        AccountRepository accountRepository = mock(AccountRepository.class);
        Account account = new Account();
        account.setUserId(userId);
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AccountAggregatorLinkRepository aaLinks = mock(AccountAggregatorLinkRepository.class);
        when(aaLinks.findByAccountIdAndStatus(accountId, AccountAggregatorLinkStatus.ACTIVE))
                .thenReturn(Optional.of(new com.finora.integrations.setu.AccountAggregatorLink()));

        ImportService.AccountAggregatorGuard guard =
                new ImportService.AccountAggregatorGuard(accountRepository, aaLinks);

        assertThatThrownBy(() -> guard.checkNotActivelySynced(userId, accountId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
    }

    @Test
    void allowsAnAccountWhoseAaLinkIsNotActive() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        AccountRepository accountRepository = mock(AccountRepository.class);
        Account account = new Account();
        account.setUserId(userId);
        account.setPrimarySource(Account.PrimarySource.MANUAL);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AccountAggregatorLinkRepository aaLinks = mock(AccountAggregatorLinkRepository.class);

        ImportService.AccountAggregatorGuard guard =
                new ImportService.AccountAggregatorGuard(accountRepository, aaLinks);

        guard.checkNotActivelySynced(userId, accountId); // does not throw
    }
}
