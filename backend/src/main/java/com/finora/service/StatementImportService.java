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
import com.finora.repository.StatementImportRepository.StatementMetadata;
import com.finora.repository.TransactionRepository;
import com.finora.accounts.AccountBalanceConvention;
import com.finora.accounts.AccountDto;
import com.finora.imports.ConfirmedRowIntegrity;
import com.finora.imports.ImportService;
import com.finora.security.OwnershipGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
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
    private final com.finora.imports.storage.StatementContentService statementContentService;

    public StatementImportService(StatementImportRepository statementImportRepository, AccountRepository accountRepository,
                                   CategoryRepository categoryRepository, TransactionRepository transactionRepository,
                                   ReconciliationService reconciliationService, RecurringService recurringService,
                                   ImportService importService, AuditService auditService,
                                   BankManagementService bankManagementService,
                                   com.finora.imports.storage.StatementContentService statementContentService) {
        this.statementImportRepository = statementImportRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.reconciliationService = reconciliationService;
        this.recurringService = recurringService;
        this.importService = importService;
        this.auditService = auditService;
        this.bankManagementService = bankManagementService;
        this.statementContentService = statementContentService;
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

        // Metadata projection, not the entity-returning finder: see
        // StatementImportRepository.StatementMetadata's own doc comment for why this method was
        // one of the six callers found still loading fileContent eagerly through it.
        Map<UUID, List<StatementMetadata>> byAccount = statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId)
                .stream().collect(Collectors.groupingBy(StatementMetadata::getAccountId, LinkedHashMap::new, Collectors.toList()));

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
     *  doc comment. Backs the Statement Imports page's per-import duplicate count.
     *
     *  <p>Package-private, not private: DataExportService (same package) reuses this rather than
     *  re-deriving the same grouped-COUNT query a second time.
     *
     *  <p><b>Trust boundary, for the next caller in this package (review note):</b> widening this
     *  from {@code private} removed its previous single-caller guarantee -- {@code userId} here is
     *  taken on faith, with no ownership/ScopedIdentityLookup check of its own, unlike every
     *  per-entity accessor in this class (which all route through {@code getOwned}/{@code
     *  OwnershipGuard}). Safe today because both callers ({@link #listGroupedByAccount} and {@code
     *  DataExportService.buildBundle}) already pass only the authenticated caller's own id. A
     *  future caller in {@code com.finora.service} that passes a less-trusted id (an admin tool,
     *  a batch job iterating other users' ids) would get that OTHER user's duplicate counts with
     *  nothing here or at compile time catching it -- scope the caller, not this method, or add a
     *  real check here if that stops being true. */
    Map<UUID, Integer> duplicateCountsByStatementImport(UUID userId) {
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

    /** Carries the content type alongside the bytes so the controller does not have to guess it.
     *  {@code contentType} is derived from {@link StatementImport#getSourceFormat()} — the format
     *  recorded at upload, which that field's own comment describes as "explicit, not inferred
     *  from fileName's extension." The controller previously hardcoded {@code text/csv} for every
     *  download, PDFs included. */
    public record FileDownload(String fileName, byte[] content, String contentType) {}

    /** Maps the recorded source format to a media type. Deliberately a switch over the formats
     *  this system actually stores rather than a filename-extension lookup: the extension is
     *  attacker-influenced and, more to the point, the authoritative answer is already on the row. */
    private static String contentTypeFor(String sourceFormat) {
        if (sourceFormat == null) return "application/octet-stream";
        return switch (sourceFormat.toUpperCase()) {
            case "CSV" -> "text/csv";
            case "PDF" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    @Transactional(readOnly = true)
    public FileDownload getFile(UUID userId, UUID statementImportId) {
        StatementImport si = getOwned(userId, statementImportId);
        // Resolved inside the transaction on purpose: for a row still holding its bytes in the
        // database, fileContent is lazily fetched, and reading it outside would throw
        // LazyInitializationException. An object-storage read does not care, but the call has to be
        // safe for both states while the migration is in progress.
        return new FileDownload(si.getFileName(), statementContentService.read(si),
                contentTypeFor(si.getSourceFormat()));
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
        byte[] content = statementContentService.read(si);
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
     *
     * <p><b>BH-006.</b> This used to skip {@link ConfirmedRowIntegrity} entirely — the check
     * {@code confirmSession} runs against its persisted {@code ImportSession} had nothing to run
     * against here, because {@link #reimport} doesn't persist one; it returns the staged rows to
     * the client and forgets them. So {@code request.rows()} went straight into
     * {@code importService.confirm}, unchecked against the document this row claims to be from.
     * Reproduced: a row dated 2099-01-01, for ₹999,999, present in no statement this account has
     * ever had, confirmed successfully and posted to the ledger.
     *
     * <p>The fix re-parses the stored bytes — the same ones {@link #reimport} just parsed for the
     * client to review — and runs the confirmed rows through the identical
     * {@link ConfirmedRowIntegrity#requireSameRows} check {@code confirmSession} already trusts,
     * rather than a second, parallel validation invented for this path. The server derives its own
     * answer to "what does this document actually say" from the bytes it is holding; nothing the
     * client sent is taken as that answer, including a row that merely happens to look plausible.
     *
     * <p>Re-parsing rather than caching the first parse costs one extra pass over the file, paid
     * once per confirm — and it is the only source of server truth available without persisting an
     * {@code ImportSession} for reimport too, which is a larger change than this fix.
     *
     * <p><b>Regression this introduced, and its fix.</b> Re-parsing a password-protected PDF needs
     * its password, and the first version of this fix always passed {@code null} — {@link
     * com.finora.dto.ImportDto.ConfirmRequest} had nowhere to carry one, so every reimport-confirm
     * of a protected statement failed with {@code IMPORT_PDF_PASSWORD_REQUIRED} unconditionally,
     * with no client-side way to recover: {@code reimport()}'s own password prompt (below) unlocks
     * the document for STAGING, but the password was then dropped rather than carried into the
     * separate confirm call. A sibling fix earlier the same day (BH-023, see {@code
     * ConfirmedRowIntegrity}) had already named this exact trade-off and deliberately left
     * confirmReimport unguarded rather than ship it — that judgment was overridden here without
     * fully reckoning with the severity: not a degraded case, an unconditional dead end for the
     * document type most Indian bank statements actually use. {@code ConfirmRequest.password()} now
     * carries it, so a client that already asked for the password once (to stage) can send the same
     * one again here rather than needing a second, new prompt.
     */
    @Transactional
    public com.finora.dto.ImportDto.ConfirmResponse confirmReimport(
            UUID userId, UUID statementImportId, com.finora.dto.ImportDto.ConfirmRequest request) throws IOException {
        StatementImport original = getOwned(userId, statementImportId);
        byte[] content = statementContentService.read(original);

        var freshStaging = importService.parseAndStageAnyFormat(userId, original.getSourceFormat(),
                original.getFileName(), content, original.getSourceSectionIndex(), request.password());
        ConfirmedRowIntegrity.requireSameRows(freshStaging.rows(), request.rows());

        // Bug fix: this used to stop at statementPeriodStart/End, silently dropping
        // totalAmountDue/paymentDueDate even though the incoming request carries them (the
        // frontend echoes them the same way it echoes the period) -- every re-import of a
        // credit-card statement wiped its own total-due/due-date on the new StatementImport row.
        var scoped = new com.finora.dto.ImportDto.ConfirmRequest(
                null, request.rows(), original.getAccountId(), null,
                request.statementOpeningBalance(), request.statementClosingBalance(), null,
                request.statementPeriodStart(), request.statementPeriodEnd(),
                request.totalAmountDue(), request.paymentDueDate());
        return importService.confirm(userId, original.getFileName(), content, scoped);
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

        // Bug 17, the half that stops the fix drifting. Once confirm() moves the account balance by
        // the net effect of the rows it inserts, deleting those rows has to move it back by exactly
        // the same amount, or an import/delete cycle leaves the balance permanently overstated. The
        // same negate-the-delta reversal TransactionService.delete already performs per row, done
        // once for the batch through the shared convention so the two cannot disagree about the
        // credit-card inversion.
        //
        // Deliberately reverses even when the import had originally set the balance from a stated
        // closing figure: whichever way it was written, these transactions are what the balance is
        // now standing on, and removing them without moving it leaves the column describing a
        // ledger that no longer exists.
        if (!toRemove.isEmpty()) {
            accountRepository.findById(statementImport.getAccountId()).ifPresent(account -> {
                BigDecimal reversal = AccountBalanceConvention
                        .netDelta(account.getAccountType(), toRemove).negate();
                if (reversal.signum() != 0) {
                    account.setBalance(account.getBalance().add(reversal));
                    accountRepository.save(account);
                }
            });
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
