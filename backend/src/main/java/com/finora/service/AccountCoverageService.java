package com.finora.service;

import com.finora.dto.CoverageDto;
import com.finora.entity.Account;
import com.finora.imports.StatementCoverageAnalyzer;
import com.finora.imports.StatementCoverageAnalyzer.StatementPeriod;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementImportRepository.StatementMetadata;
import com.finora.security.OwnershipGuard;
import com.finora.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The one place {@code StatementCoverageAnalyzer} is actually invoked for Phase 1 (docs/proposals/
 * statement-continuity-and-coverage-integrity-proposal.md §5's "one function, three consumers" --
 * the user-facing and admin controllers are two of the three; Insights' own call site is Phase 3,
 * not this one). Owns ownership verification and the {@code StatementMetadata} -> {@code
 * StatementPeriod} mapping; the analyzer itself stays a pure function with no repository access.
 */
@Service
public class AccountCoverageService {

    private final AccountRepository accountRepository;
    private final StatementImportRepository statementImportRepository;

    public AccountCoverageService(AccountRepository accountRepository,
                                   StatementImportRepository statementImportRepository) {
        this.accountRepository = accountRepository;
        this.statementImportRepository = statementImportRepository;
    }

    /** @throws com.finora.exception.ApiException 404 if no such account, 403 if it belongs to
     *          someone else -- {@link OwnershipGuard} enforces both, not a hand-rolled check. */
    @Transactional(readOnly = true)
    public CoverageDto forAccount(UUID userId, UUID accountId) {
        OwnershipGuard.requireOwned(accountRepository.findById(accountId), Account::getUserId, userId, "Account");
        return buildReport(userId, accountId);
    }

    /** The admin path (§9: {@code GET /api/v1/admin/accounts/{accountId}/coverage}) looks up by
     *  accountId alone -- no {@code userId} is known ahead of time, and none is needed:
     *  {@code PLATFORM_DIAGNOSTICS_VIEW} already authorizes cross-user access at the controller
     *  level, the same way {@code AdminImportTraceController} looks up by reference/jobId alone.
     *  The account's own {@code userId} is read off the found row to scope the statement query. */
    @Transactional(readOnly = true)
    public CoverageDto forAccountAsAdmin(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        return buildReport(account.getUserId(), accountId);
    }

    private CoverageDto buildReport(UUID userId, UUID accountId) {
        var periods = statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(userId, accountId)
                .stream()
                .map(AccountCoverageService::toStatementPeriod)
                .toList();

        var report = StatementCoverageAnalyzer.analyze(periods);
        return CoverageDto.from(accountId, report);
    }

    private static StatementPeriod toStatementPeriod(StatementMetadata m) {
        return new StatementPeriod(m.getId(), m.getStatementPeriodStart(), m.getStatementPeriodEnd(),
                m.getOpeningBalance(), m.getClosingBalance());
    }
}
