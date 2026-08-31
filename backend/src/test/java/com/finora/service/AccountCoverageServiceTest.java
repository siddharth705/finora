package com.finora.service;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementImportRepository.StatementMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the two Phase 1 call sites (docs/proposals/statement-continuity-and-coverage-integrity-
 * proposal.md §0.14) -- the user-facing path, which must enforce ownership via {@link
 * com.finora.security.OwnershipGuard}, and the admin path, which looks up by accountId alone and
 * derives the target userId from the account row itself. The analyzer's own logic is covered
 * exhaustively in StatementCoverageAnalyzerTest; this class only covers the wiring around it.
 */
class AccountCoverageServiceTest {

    private AccountRepository accountRepository;
    private StatementImportRepository statementImportRepository;
    private AccountCoverageService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        service = new AccountCoverageService(accountRepository, statementImportRepository);
    }

    private Account account(UUID id, UUID ownerId) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        account.setUserId(ownerId);
        return account;
    }

    private StatementMetadata metadata(UUID statementId, LocalDate start, LocalDate end,
                                        BigDecimal opening, BigDecimal closing) {
        StatementMetadata m = mock(StatementMetadata.class);
        when(m.getId()).thenReturn(statementId);
        when(m.getStatementPeriodStart()).thenReturn(start);
        when(m.getStatementPeriodEnd()).thenReturn(end);
        when(m.getOpeningBalance()).thenReturn(opening);
        when(m.getClosingBalance()).thenReturn(closing);
        return m;
    }

    @Test
    void forAccount_returnsCoverage_whenTheAccountBelongsToTheCaller() {
        // Built as locals BEFORE the outer when(...).thenReturn(...) call -- a mocked projection's
        // own when(...) calls inside that same statement trip Mockito's UnfinishedStubbingException
        // (this codebase's own established trap, see StatementCoverageAnalyzerTest's sibling tests
        // for the pattern this avoids).
        StatementMetadata may = metadata(UUID.randomUUID(), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                new BigDecimal("1000"), new BigDecimal("2000"));
        StatementMetadata july = metadata(UUID.randomUUID(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                new BigDecimal("2500"), new BigDecimal("3000"));

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, userId)));
        when(statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(userId, accountId))
                .thenReturn(List.of(may, july));

        var dto = service.forAccount(userId, accountId);

        assertThat(dto.accountId()).isEqualTo(accountId);
        assertThat(dto.hasGaps()).isTrue();
        assertThat(dto.gaps()).hasSize(1);
        assertThat(dto.gaps().get(0).gapStart()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void forAccount_throws404_whenNoSuchAccountExists() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forAccount(userId, accountId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void forAccount_throws403_whenTheAccountBelongsToSomeoneElse() {
        UUID someoneElse = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, someoneElse)));

        assertThatThrownBy(() -> service.forAccount(userId, accountId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(403));

        // The forbidden path must never fall through to fetching another user's statements.
        org.mockito.Mockito.verifyNoInteractions(statementImportRepository);
    }

    @Test
    void forAccountAsAdmin_looksUpByAccountIdAlone_andDerivesTheOwnerFromTheAccountRow() {
        UUID theAccountsRealOwner = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, theAccountsRealOwner)));
        when(statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(theAccountsRealOwner, accountId))
                .thenReturn(List.of());

        var dto = service.forAccountAsAdmin(accountId);

        assertThat(dto.accountId()).isEqualTo(accountId);
        assertThat(dto.coverageStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void forAccountAsAdmin_throws404_whenNoSuchAccountExists() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forAccountAsAdmin(accountId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(404));
    }
}
