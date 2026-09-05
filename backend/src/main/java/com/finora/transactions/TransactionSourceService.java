package com.finora.transactions;

import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.security.OwnershipGuard;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * "Where did this number come from?" -- a small, deliberately separate service rather than a new
 * {@code TransactionService} dependency, the same reasoning {@link TransactionExplanationService}'s
 * own class doc gives for staying out of it. Nothing here computes a new answer -- it reads the
 * import pipeline's own record of which statement row a transaction came from and renders it.
 *
 * <p>{@code statementImportId} and {@code accountId} are read off entities the already-owned
 * transaction points to, not off caller-supplied input, so this deliberately does not run a second
 * {@link OwnershipGuard} check on the {@link StatementImport}/{@link Account} lookups -- the same
 * trust boundary {@code TransactionExplanationService} relies on for its own {@code
 * categoryRuleRepository}/{@code merchantRepository}/{@code categoryRepository} lookups.
 */
@Service
public class TransactionSourceService {

    private final TransactionRepository transactionRepository;
    private final StatementImportRepository statementImportRepository;
    private final AccountRepository accountRepository;

    public TransactionSourceService(TransactionRepository transactionRepository,
                                     StatementImportRepository statementImportRepository,
                                     AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.statementImportRepository = statementImportRepository;
        this.accountRepository = accountRepository;
    }

    public TransactionSourceDto explainSource(UUID userId, UUID transactionId) {
        Transaction t = OwnershipGuard.requireOwned(
                transactionRepository.findById(transactionId), Transaction::getUserId, userId, "Transaction");

        if (t.getStatementImportId() == null || t.getSourceRowPosition() == null) {
            return TransactionSourceDto.notAvailable(t.getSource().name());
        }

        StatementImport statementImport = statementImportRepository.findById(t.getStatementImportId()).orElse(null);
        if (statementImport == null) {
            // The import row this transaction came from has since been deleted (a superseded
            // upload, or account-purge cleanup) -- the transaction itself survives, but there is
            // no longer a file/period to point to. Same "state it plainly" answer as any other
            // not-available case above, not a 404: the TRANSACTION still exists and is owned.
            return TransactionSourceDto.notAvailable(t.getSource().name());
        }

        String accountName = accountRepository.findById(statementImport.getAccountId())
                .map(Account::getName).orElse(null);

        return new TransactionSourceDto(true, t.getSource().name(), statementImport.getId(),
                statementImport.getFileName(), t.getSourceRowPosition(), statementImport.getImportedAt(),
                accountName, statementImport.getStatementPeriodStart(), statementImport.getStatementPeriodEnd());
    }
}
