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
import com.finora.imports.CoverageWarnings;
import com.finora.imports.ImportService;
import com.finora.security.OwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(StatementImportService.class);

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
        var response = importService.confirm(userId, original.getFileName(), content, scoped);

        // Bug fix (self-review, statement continuity Phase 2): this reimport-confirms via the same
        // path a first-time import takes, and `original` is never deleted -- confirm() creates a
        // genuinely new StatementImport row with the SAME period, so CoverageWarnings correctly
        // (from its own, narrower view) sees a same-period duplicate and warns about it. From the
        // user's side, that reads as "you already have a statement for this period" immediately
        // after an action that just successfully corrected one -- true in the letter (the old row
        // genuinely still exists, unreplaced by a plain reimport) but actively misleading in this
        // context, since it is not a statement worth offering to "Import as a replacement" against
        // -- it is the SAME correction the user just made. Stripped here, at the one call site that
        // actually knows this "duplicate" is the statement being reimported, rather than threading
        // a "this confirm is a reimport of X" signal through ImportService's whole confirm/
        // persistSection/summarise call graph for it.
        //
        // Bug fix (self-review, Phase 4 follow-up): this used to strip EVERY duplicate-period
        // warning by string prefix while only conditionally clearing duplicateOfStatementId (when
        // it happened to equal `original`'s own id) -- two independently-flattened views of
        // "possibly several overlaps" that had no way to stay in agreement. Whenever this reimport
        // ALSO exact-duplicated a second, unrelated statement (a real, actionable finding -- the
        // whole reason this feature exists), one of two things happened depending purely on which
        // overlap CoverageWarnings.duplicateOfStatementId happened to return first: either the
        // unrelated duplicate's id survived while its explaining sentence was stripped alongside
        // `original`'s (an id the summary screen could never render a button for, since it only
        // renders when warnings is non-empty), or the id got cleared to null and BOTH sentences
        // vanished, silently dropping the second duplicate entirely. duplicateOverlapsFor gives the
        // per-overlap (id, sentence) pairing needed to remove only the ONE overlap against
        // `original` and leave any other duplicate -- id and sentence together -- exactly as an
        // ordinary confirm's response would carry it.
        List<CoverageWarnings.DuplicateOverlap> overlaps = importService.duplicateOverlapsFor(
                userId, original.getAccountId(), response.statementImportId(),
                response.statementPeriodStart(), response.statementPeriodEnd());
        List<CoverageWarnings.DuplicateOverlap> realDuplicates = overlaps.stream()
                .filter(o -> !o.otherStatementId().equals(statementImportId))
                .toList();

        List<String> warnings = new ArrayList<>(response.warnings().stream()
                .filter(w -> !w.startsWith(CoverageWarnings.DUPLICATE_PERIOD_WARNING_PREFIX))
                .toList());
        realDuplicates.forEach(o -> warnings.add(o.warning()));

        return response.withWarningsAndDuplicateOfStatementId(
                warnings,
                realDuplicates.isEmpty() ? null : realDuplicates.get(0).otherStatementId());
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
        // ABSOLUTE-mode rows are reversed separately from ADDITIVE/NONE/UNKNOWN_LEGACY, and
        // unconditionally (not gated on whether this statement had any transactions): an ABSOLUTE
        // confirm's SET can move the balance even for a zero-row statement, if its stated closing
        // balance corroborated against a carried-forward opening figure that differed from live
        // Account.balance at that moment (see OpeningBalanceCarryForward) -- reverseAbsoluteContribution
        // reads the persisted snapshot and live pointer, not this statement's rows, so row count is
        // irrelevant to it. The row-based reversal below it is unchanged: negating a still-live
        // ADDITIVE-mode row's current net effect (or an UNKNOWN_LEGACY row's, unfixed here --
        // deliberately out of scope, see the design spec) is only correct when there are rows to
        // sum, unlike ABSOLUTE's snapshot-based approach.
        if (statementImport.getBalanceApplicationMode() == StatementImport.BalanceApplicationMode.ABSOLUTE
                || !toRemove.isEmpty()) {
            accountRepository.findById(statementImport.getAccountId()).ifPresent(account -> {
                if (statementImport.getBalanceApplicationMode() == StatementImport.BalanceApplicationMode.ABSOLUTE) {
                    ReversalOutcome outcome = reverseAbsoluteContribution(statementImport, account);
                    if (outcome == ReversalOutcome.NO_SNAPSHOT) {
                        log.warn("Cannot reverse ABSOLUTE contribution for statement {}: no pre-SET "
                                + "snapshot (row predates automatic reversal tracking). Balance not "
                                + "adjusted; verify manually if needed.", statementImport.getId());
                    }
                    return;
                }
                if (toRemove.isEmpty()) return;
                // Excludes an already-DUPLICATE-flagged row: its contribution to Account.balance
                // was already reversed once, at the original statement's own confirm time
                // (ImportService.summarise's BH-003 correction) -- summing it again here would
                // move the balance a second time for a row that currently contributes nothing.
                // Also excludes SUPERSEDED (#631 missed this second trigger of the same bug):
                // StatementImportService.supersede() marks an ADDITIVE-mode original's rows
                // SUPERSEDED and reverses their contribution in that same call, so a SUPERSEDED
                // row's current net contribution is zero too -- deleting an already-superseded
                // statement must not reverse it a second time here.
                // TRANSFER/REFUND/REVERSAL/INVESTMENT_TRANSFER rows stay included: those
                // classifications only affect expense/income REPORTING (RefundNetting.reportable),
                // not Account.balance -- the cash genuinely moved, so the balance still reflects it.
                List<Transaction> stillContributing = toRemove.stream()
                        .filter(t -> t.getIsDuplicateOf() == null
                                && t.getReconciliationStatus() != Transaction.ReconciliationStatus.SUPERSEDED)
                        .toList();
                BigDecimal reversal = AccountBalanceConvention
                        .netDelta(account.getAccountType(), stillContributing).negate();
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

    private enum ReversalOutcome { REVERSED, MOOT, NO_SNAPSHOT }

    /**
     * Reverses an ABSOLUTE-mode statement's contribution to {@code Account.balance} -- the SET
     * {@code ImportService.persistSection} performed at this statement's own confirm time. Shared
     * by {@code supersede} and {@code delete}, the only two callers that ever need to undo one.
     *
     * <p>A SET is only safely reversible while it is still the account's live anchor: {@link
     * Account#getLastAbsoluteSetStatementId()} tells whether some OTHER SET (a later-period
     * ABSOLUTE statement, or a manual {@code AccountService.update} balance edit) has already
     * overwritten it, in which case {@code original}'s contribution is already fully gone and
     * there is nothing to reverse -- correct, not a gap. See the "absolute balance reversal"
     * design spec's "live anchor" section.
     *
     * <p>{@code original.getBalanceBeforeAbsoluteSet()} is null for any row confirmed before that
     * field existed -- {@code BalanceApplicationMode} says ABSOLUTE, but nothing captured what the
     * balance was before the SET. Guessing risks the exact corruption this exists to prevent, so
     * this is treated the same conservative way {@code UNKNOWN_LEGACY} already is: no reversal,
     * caller surfaces a warning instead.
     */
    private ReversalOutcome reverseAbsoluteContribution(StatementImport original, Account account) {
        if (original.getBalanceBeforeAbsoluteSet() == null) {
            return ReversalOutcome.NO_SNAPSHOT;
        }
        if (!original.getId().equals(account.getLastAbsoluteSetStatementId())) {
            return ReversalOutcome.MOOT;
        }
        BigDecimal delta = original.getBalanceBeforeAbsoluteSet().subtract(original.getClosingBalance());
        if (delta.signum() != 0) {
            account.setBalance(account.getBalance().add(delta));
        }
        account.setLastAbsoluteSetStatementId(null);
        accountRepository.save(account);
        return ReversalOutcome.REVERSED;
    }

    /**
     * "Import this one as a replacement?" (docs/proposals/statement-continuity-and-coverage-
     * integrity-proposal.md §0.3/§0.23): {@code replacementId} has already been confirmed as its
     * own statement, covering the exact same period as {@code originalId} -- this marks the
     * original superseded rather than deleting it, so its history stays queryable the same way a
     * TRANSFER-classified transaction stays in the ledger instead of being removed.
     *
     * <p>Deliberately two separate calls from the client (confirm the replacement, then supersede
     * the original) rather than one combined request: threading a "this confirm also supersedes X"
     * signal through {@code ImportService}'s confirm/persistSection/summarise call graph would
     * touch every one of its four {@code confirm} overloads for what only this one caller needs --
     * the same reasoning {@code confirmReimport}'s own duplicate-warning fix gave for staying out
     * of that call graph.
     *
     * <p>The balance-reversal decision is read from {@code original}'s own {@link
     * StatementImport.BalanceApplicationMode}, persisted at ITS confirm time -- never recomputed
     * here. See that field's own doc comment for why recomputation is unsafe (totalCredits/
     * totalDebits were never persisted, and Transaction.amount is editable after import).
     *
     * <p>When {@code original} is ABSOLUTE, {@link #reverseAbsoluteContribution} decides whether
     * a reversal is still possible: it is a no-op when replacement's own confirm ALSO landed in
     * ABSOLUTE mode (its own SET already fully overwrote original's), and reverses correctly
     * otherwise. See that method's own doc comment.
     *
     * <p><b>ADDITIVE original skips its own reversal against an ABSOLUTE replacement, for the
     * mirror-image reason.</b> ABSOLUTE mode does not add to {@code Account.balance} -- it
     * OVERWRITES it with the statement's own stated closing balance ({@code
     * ImportService.persistSection}), discarding whatever value was there before, original's
     * still-live ADDITIVE contribution included. Unlike the ABSOLUTE-original case above, this one
     * isn't refused: the overwrite already leaves the balance correct on its own, so there's simply
     * nothing left to reverse -- see the ADDITIVE case below.
     */
    @Transactional
    public com.finora.dto.StatementImportDto.SupersedeResult supersede(UUID userId, UUID originalId, UUID replacementId) {
        if (originalId.equals(replacementId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A statement cannot supersede itself.");
        }
        StatementImport original = getOwned(userId, originalId);
        StatementImport replacement = getOwned(userId, replacementId);

        if (!original.getAccountId().equals(replacement.getAccountId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A statement can only be superseded by a replacement for the same account.");
        }
        if (!Objects.equals(original.getStatementPeriodStart(), replacement.getStatementPeriodStart())
                || !Objects.equals(original.getStatementPeriodEnd(), replacement.getStatementPeriodEnd())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A statement can only be superseded by a replacement covering the exact same period.");
        }
        if (original.getSupersededBy() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This statement has already been superseded.");
        }
        if (replacement.getSupersededBy() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The replacement statement has itself already been superseded.");
        }
        // Only OK-status rows -- a row already excluded for its own reason (DUPLICATE/TRANSFER/
        // REFUND/REVERSAL/INVESTMENT_TRANSFER) is already invisible to RefundNetting.reportable(),
        // and overwriting its status here would lose the true reason it was excluded, same
        // "preserve the specific classification" principle StatementImportService.delete's own
        // pointer cleanup already follows.
        List<Transaction> originalTransactions = transactionRepository.findByStatementImportId(originalId);
        List<Transaction> toSupersede = originalTransactions.stream()
                .filter(t -> t.getReconciliationStatus() == Transaction.ReconciliationStatus.OK)
                .toList();
        for (Transaction t : toSupersede) {
            t.setReconciliationStatus(Transaction.ReconciliationStatus.SUPERSEDED);
            transactionRepository.save(t);
        }

        boolean balanceReversed = false;
        String warning = null;
        switch (original.getBalanceApplicationMode()) {
            case ADDITIVE -> {
                // Skip entirely when replacement is ABSOLUTE: that mode doesn't add to
                // Account.balance, it OVERWRITES it with replacement's own stated closing balance
                // (ImportService.persistSection) -- discarding original's ADDITIVE contribution
                // along with everything else that predates it. Reversing original's delta against a
                // balance that already discarded it (rather than one it was layered on top of) would
                // move the balance by that amount for no reason; see this method's own doc comment
                // and SupersedeSkipsReversalWhenReplacementOverwritesTheBalanceIT for the concrete
                // numeric case.
                if (replacement.getBalanceApplicationMode() != StatementImport.BalanceApplicationMode.ABSOLUTE) {
                    // Excludes an already-DUPLICATE-flagged row: its contribution to Account.balance
                    // was already reversed once, at the original statement's own confirm time
                    // (ImportService.summarise's BH-003 correction) -- summing it again here would
                    // move the balance a second time for a row that currently contributes nothing.
                    // TRANSFER/REFUND/REVERSAL/INVESTMENT_TRANSFER rows stay included: those
                    // classifications only affect expense/income REPORTING (RefundNetting.reportable),
                    // not Account.balance -- the cash genuinely moved, so the balance still reflects it.
                    List<Transaction> stillContributing = originalTransactions.stream()
                            .filter(t -> t.getIsDuplicateOf() == null)
                            .toList();
                    if (!stillContributing.isEmpty()) {
                        Optional<Account> account = accountRepository.findById(original.getAccountId());
                        if (account.isPresent()) {
                            BigDecimal reversal = AccountBalanceConvention
                                    .netDelta(account.get().getAccountType(), stillContributing).negate();
                            if (reversal.signum() != 0) {
                                account.get().setBalance(account.get().getBalance().add(reversal));
                                accountRepository.save(account.get());
                                balanceReversed = true;
                            }
                        }
                    }
                }
            }
            case ABSOLUTE -> {
                Optional<Account> account = accountRepository.findById(original.getAccountId());
                if (account.isPresent()) {
                    ReversalOutcome outcome = reverseAbsoluteContribution(original, account.get());
                    balanceReversed = outcome == ReversalOutcome.REVERSED;
                    if (outcome == ReversalOutcome.NO_SNAPSHOT) {
                        warning = "This statement predates automatic balance-reversal tracking, so its "
                                + "contribution to the account balance could not be automatically reversed. "
                                + "An administrator should verify this account's balance.";
                    }
                }
            }
            case UNKNOWN_LEGACY -> warning = "This statement predates balance-application tracking, so its "
                    + "contribution to the account balance could not be automatically reversed. An "
                    + "administrator should verify this account's balance.";
            case NONE -> { /* no reversal -- nothing was ever moved, see BalanceApplicationMode's own doc comment */ }
        }

        original.setSupersededBy(replacementId);
        statementImportRepository.save(original);

        if (!toSupersede.isEmpty()) {
            reconciliationService.reconcileForUser(userId);
            recurringService.detectForUser(userId);
        }

        auditService.record(userId, "STATEMENT_IMPORT_SUPERSEDED", "StatementImport", originalId,
                Map.of("fileName", original.getFileName(), "supersededBy", replacementId,
                        "balanceApplicationMode", original.getBalanceApplicationMode().name(),
                        "balanceReversed", balanceReversed));

        return new com.finora.dto.StatementImportDto.SupersedeResult(originalId, replacementId, balanceReversed, warning);
    }
}
