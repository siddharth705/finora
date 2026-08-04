package com.finora.service;

import com.finora.dto.StatementImportDto.AccountGroup;
import com.finora.dto.StatementImportDto.Summary;
import com.finora.transactions.TransactionDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.accounts.AccountDto;
import com.finora.imports.ImportService;
import com.finora.security.OwnershipGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Backs the account-organized Statement History (see the frontend's /app/statements page):
 * users think about their finances by account, not by which file they uploaded, so history is
 * grouped by account here rather than exposed as one flat list of imports.
 */
@Service
public class StatementImportService {

    private final StatementImportRepository statementImportRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final ReconciliationService reconciliationService;
    private final RecurringService recurringService;
    private final ImportService importService;
    private final AuditService auditService;
    private final BankManagementService bankManagementService;

    public StatementImportService(StatementImportRepository statementImportRepository, AccountRepository accountRepository,
                                   CategoryRepository categoryRepository, TransactionRepository transactionRepository,
                                   ReconciliationService reconciliationService, RecurringService recurringService,
                                   ImportService importService, AuditService auditService,
                                   BankManagementService bankManagementService) {
        this.statementImportRepository = statementImportRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.reconciliationService = reconciliationService;
        this.recurringService = recurringService;
        this.importService = importService;
        this.auditService = auditService;
        this.bankManagementService = bankManagementService;
    }

    // How long a deleted account's statement history stays visible in Statement History before
    // it's dropped from the response for good. Chosen to give "oops, wrong account" a real
    // window to notice and fix without keeping dead accounts cluttering this page forever.
    private static final java.time.Duration DELETED_ACCOUNT_RETENTION = java.time.Duration.ofDays(7);

    @Transactional(readOnly = true)
    public List<AccountGroup> listGroupedByAccount(UUID userId) {
        // Includes soft-deleted accounts (see AccountRepository.findByUserIdIncludingDeleted) —
        // findByUserId alone would silently exclude any account deleted within the retention
        // window too, which is exactly the case this method needs to still show.
        Map<UUID, Account> accountsById = accountRepository.findByUserIdIncludingDeleted(userId).stream()
                .collect(Collectors.toMap(Account::getId, a -> a));

        Map<UUID, List<StatementImport>> byAccount = statementImportRepository.findByUserIdOrderByImportedAtDesc(userId)
                .stream().collect(Collectors.groupingBy(StatementImport::getAccountId, LinkedHashMap::new, Collectors.toList()));

        Instant cutoff = Instant.now().minus(DELETED_ACCOUNT_RETENTION);
        Map<UUID, Integer> duplicateCounts = duplicateCountsByStatementImport(userId);

        List<AccountGroup> groups = new ArrayList<>();
        for (var entry : byAccount.entrySet()) {
            Account account = accountsById.get(entry.getKey());

            // Account outright gone from the table (shouldn't happen under normal soft-delete,
            // but defensive) — same treatment as "past its retention window": drop the group.
            if (account == null) continue;

            boolean isDeleted = account.getDeletedAt() != null;
            if (isDeleted && account.getDeletedAt().isBefore(cutoff)) {
                // Past the 7-day grace period — this account's statement history no longer
                // appears here at all.
                continue;
            }

            List<Summary> statements = entry.getValue().stream()
                    .map(s -> Summary.from(s, duplicateCounts.getOrDefault(s.getId(), 0)))
                    .toList();
            AccountDto.BankDto bank = bankManagementService.resolve(account.getBankId());
            groups.add(new AccountGroup(
                    entry.getKey(), account.getName(), account.getAccountType().name(), bank,
                    statements, isDeleted, account.getDeletedAt()));
        }
        return groups;
    }

    /** One grouped COUNT query for every statement import this user has, rather than one query
     *  per statement — see TransactionRepository.countDuplicatesByStatementImportForUser's own
     *  doc comment. Backs the Statement Imports page's per-import duplicate count. */
    private Map<UUID, Integer> duplicateCountsByStatementImport(UUID userId) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (var row : transactionRepository.countDuplicatesByStatementImportForUser(
                userId, Transaction.ReconciliationStatus.DUPLICATE)) {
            counts.put(row.getStatementImportId(), row.getCount().intValue());
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public Summary getDetail(UUID userId, UUID statementImportId) {
        StatementImport si = getOwned(userId, statementImportId);
        long duplicateCount = transactionRepository.findByStatementImportId(statementImportId).stream()
                .filter(t -> t.getReconciliationStatus() == Transaction.ReconciliationStatus.DUPLICATE)
                .count();
        return Summary.from(si, (int) duplicateCount);
    }

    @Transactional(readOnly = true)
    public List<TransactionDto> getTransactions(UUID userId, UUID statementImportId) {
        getOwned(userId, statementImportId);
        Map<UUID, String> namesById = categoryRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        return transactionRepository.findByStatementImportId(statementImportId).stream()
                .map(t -> TransactionDto.from(t, namesById.getOrDefault(t.getCategoryId(), "Uncategorized")))
                .toList();
    }

    public record FileDownload(String fileName, byte[] content) {}

    @Transactional(readOnly = true)
    public FileDownload getFile(UUID userId, UUID statementImportId) {
        StatementImport si = getOwned(userId, statementImportId);
        // fileContent is lazily fetched — accessing it here, inside the transaction, is what
        // actually triggers the load rather than throwing LazyInitializationException later.
        return new FileDownload(si.getFileName(), si.getFileContent());
    }

    /**
     * "Re-import Statement": replays the exact bytes originally uploaded back through the normal
     * staging pipeline, scoped to the account it already belongs to (no "create new account"
     * choice needed — see StatementImportDto.ReimportResult). The review/confirm step after this
     * is identical to a first-time import, including duplicate detection against everything
     * already on the books — including this same statement's own prior transactions.
     *
     * @param password the document open password when the stored file is a protected PDF, or null.
     *   The stored bytes are the ORIGINAL encrypted ones and the upload-time password is never
     *   persisted, so a protected statement cannot be replayed without being given it again:
     *   calling with null yields IMPORT_PDF_PASSWORD_REQUIRED, which is what prompts the user.
     */
    @Transactional(readOnly = true)
    public com.finora.dto.StatementImportDto.ReimportResult reimport(UUID userId, UUID statementImportId, String password) throws Exception {
        StatementImport si = getOwned(userId, statementImportId);
        byte[] content = si.getFileContent();
        // Bug fix: this used to unconditionally call the CSV-only byte-stream overload, which
        // would try to CSV-parse raw PDF bytes for any statement originally uploaded as a PDF
        // (Milestone 1). Now routes by the explicit sourceFormat recorded on this row at
        // confirm() time, not the filename's extension -- see
        // ImportService.parseAndStageAnyFormat's own doc comment for why that's more robust.
        var staging = importService.parseAndStageAnyFormat(userId, si.getSourceFormat(), si.getFileName(), content,
                si.getSourceSectionIndex(), password);

        Account account = accountRepository.findById(si.getAccountId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "The account this statement was imported into no longer exists."));
        return new com.finora.dto.StatementImportDto.ReimportResult(staging, account.getId(), account.getName());
    }

    /**
     * Confirms a re-import. Unlike the first-time confirm() (which needs a fresh multipart file
     * upload to capture the bytes for the new StatementImport row), this one already has the
     * file server-side from the original import — so it's plain JSON, and the account is forced
     * to stay the account this statement already belongs to. Re-importing into a *different*
     * account would defeat the point of "re-import" (replaying the same statement) versus just
     * doing a fresh import.
     */
    @Transactional
    public com.finora.dto.ImportDto.ConfirmResponse confirmReimport(
            UUID userId, UUID statementImportId, com.finora.dto.ImportDto.ConfirmRequest request) {
        StatementImport original = getOwned(userId, statementImportId);
        var scoped = new com.finora.dto.ImportDto.ConfirmRequest(
                null, request.rows(), original.getAccountId(), null,
                request.statementOpeningBalance(), request.statementClosingBalance());
        return importService.confirm(userId, original.getFileName(), original.getFileContent(), scoped);
    }

    /**
     * Removes only this statement's own transactions — everything else (other statements,
     * manually-entered transactions) is untouched. Any surviving transaction that had been
     * paired with one of the removed ones (as a duplicate, transfer partner, or refund target)
     * gets its reconciliation flags reset first, rather than being left pointing at a row that no
     * longer visibly exists; reconciliation then re-runs so what's left gets re-evaluated fresh.
     *
     * Bug fix: the refund case was missing here (same gap as TransactionService's
     * clearReconciliationPointersTo before its fix) -- deleting a statement that contained the
     * EXPENSE side of a matched refund pair left a surviving INCOME row's refundOfTransactionId
     * dangling and permanently stuck at ReconciliationStatus.REFUND, silently excluding it from
     * DashboardService's totals with no way to self-correct.
     */
    @Transactional
    public void delete(UUID userId, UUID statementImportId) {
        StatementImport statementImport = getOwned(userId, statementImportId);

        List<Transaction> toRemove = transactionRepository.findByStatementImportId(statementImportId);
        List<UUID> removedIds = toRemove.stream().map(Transaction::getId).toList();

        if (!removedIds.isEmpty()) {
            for (Transaction t : transactionRepository.findByIsDuplicateOfIn(removedIds)) {
                if (removedIds.contains(t.getId())) continue;
                t.setIsDuplicateOf(null);
                t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
                transactionRepository.save(t);
            }
            for (Transaction t : transactionRepository.findByTransferPairIdIn(removedIds)) {
                if (removedIds.contains(t.getId())) continue;
                t.setTransfer(false);
                t.setTransferPairId(null);
                t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
                transactionRepository.save(t);
            }
            for (Transaction t : transactionRepository.findByRefundOfTransactionIdIn(removedIds)) {
                if (removedIds.contains(t.getId())) continue;
                t.setRefundOfTransactionId(null);
                t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
                transactionRepository.save(t);
            }
        }

        transactionRepository.deleteAll(toRemove);
        statementImportRepository.delete(statementImport);

        if (!removedIds.isEmpty()) {
            // What's left might now match a *different* surviving transaction as a transfer
            // pair than it did before this batch existed — worth a fresh pass, not just a flag reset.
            reconciliationService.reconcileForUser(userId);
            // Bug fix: this call was missing here even though deleting a whole statement is
            // exactly the kind of transaction-set change that can break a recurring group (e.g.
            // removing 2 of a merchant's 4 regularly-spaced charges) -- same reasoning as
            // TransactionService's delete()/bulkDelete(), which already do this. Without it, a
            // stale recurring badge could survive on transactions from a statement that no longer
            // supports the pattern, until the user happened to open the Recurring page.
            recurringService.detectForUser(userId);
        }

        auditService.record(userId, "STATEMENT_IMPORT_DELETED", "StatementImport", statementImportId,
                Map.of("fileName", statementImport.getFileName(), "transactionsRemoved", removedIds.size()));
    }

    private StatementImport getOwned(UUID userId, UUID statementImportId) {
        return OwnershipGuard.requireOwned(statementImportRepository.findById(statementImportId),
                StatementImport::getUserId, userId, "Statement import");
    }
}
