package com.finora.integrations.google.merchant;

import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.DuplicateMatch;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.ImportSession;
import com.finora.imports.ImportSessionService;
import com.finora.imports.RowKind;
import com.finora.imports.storage.ContentAddress;
import com.finora.util.BankRegistry;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The only door from a {@link ParsedReceipt} into review — Phase C5-B, design proposal §5.1
 * ("Gmail as the first connector, not a bespoke pipeline").
 *
 * <h2>Reuse, not a parallel system</h2>
 *
 * This does not create a {@code gmail_transactions} table or a Gmail-specific review surface. It
 * builds exactly the {@link ImportSession} row a CSV or PDF upload already produces —
 * {@code stagedRowsJson} holding one {@link StagedRow}, {@code detectedAccountJson} holding a
 * synthetic {@link DetectedAccountInfo} — so the existing review UI, the existing confirm
 * endpoint, and the existing {@code ConfirmedRowIntegrity} check all handle a Gmail receipt with
 * no code path of their own devoted to it. A receipt is "another source", exactly as asked.
 *
 * <h2>Identity without a file</h2>
 *
 * {@code ImportSession} deduplicates on {@code content_hash} — normally SHA-256 of the uploaded
 * file's bytes. A receipt has no file, so this builds a tiny synthetic payload,
 * {@code "gmail:" + gmailMessageId}, and passes it through the exact same
 * {@link ImportSessionService#createSession} path every other import uses. That is not a
 * workaround bolted beside the real mechanism — {@link ContentAddress#hashOf} is plain SHA-256 of
 * whatever bytes it is given, so this produces precisely the hash the C5-A review decided on
 * (SHA-256("gmail:" + message id)), by construction, through the one function every other import
 * source's dedup already goes through. See the design proposal's dated decision note in §6.
 *
 * <h2>Every row is "default", on purpose</h2>
 *
 * There is no merchant-to-category engine behind this yet, so guessing one would be exactly the
 * "wrong answer nobody asked for" this codebase's own account-detection code refuses to do
 * elsewhere ({@code DetectedAccountInfo}'s own doc: "never guessed to fill gaps"). {@code
 * categorySource = "default"} is honest about that, and it is not a downgrade: it is what already
 * drives the review table's "low confidence" badge, so every Gmail-derived row gets that signal
 * for free, with no new UI.
 *
 * <h2>Cross-source reconciliation (C6.4, staging-time direction)</h2>
 *
 * Every receipt is checked against already-confirmed bank transactions via
 * {@link GmailReconciliationMatcher} before it is staged — the design proposal's own finding that
 * {@code DuplicateDetector}'s exact-description match structurally cannot fire between a receipt
 * (described by merchant domain) and a bank line (described by the bank's own narration). A match
 * populates {@code likelyDuplicate}/{@code duplicateMatch} exactly as CSV/PDF staging does, so
 * {@code DuplicateReview.tsx} needs no changes to show it.
 */
@Service
public class GmailStagingBridge {

    private static final DateTimeFormatter FILE_NAME_DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private final ImportSessionService importSessionService;
    private final GmailReconciliationMatcher reconciliationMatcher;

    public GmailStagingBridge(ImportSessionService importSessionService,
                               GmailReconciliationMatcher reconciliationMatcher) {
        this.importSessionService = importSessionService;
        this.reconciliationMatcher = reconciliationMatcher;
    }

    /** What staging one receipt did. */
    public enum Result { STAGED, ALREADY_STAGED }

    /**
     * Stages a validated receipt. Callers must run {@link ParsedReceiptValidator} first — this
     * method trusts that a {@link ParsedReceipt} reaching it is plausible, and does not re-check.
     *
     * @return {@link Result#ALREADY_STAGED} when a live session for this message already exists
     *         (idempotent replay: an overlapping extraction run, a retried tick) rather than
     *         creating a second one — the same dedup guarantee every other import source gets from
     *         {@code idx_import_sessions_live_content}, exercised through the same code path.
     */
    public Result stage(UUID userId, ParsedReceipt receipt) {
        byte[] identity = ("gmail:" + receipt.gmailMessageId()).getBytes(StandardCharsets.UTF_8);
        String contentHash = ContentAddress.hashOf(identity);

        Optional<ImportSession> existing =
                importSessionService.findLiveSessionByContentHash(userId, contentHash);
        if (existing.isPresent()) {
            return Result.ALREADY_STAGED;
        }

        Optional<DuplicateMatch> duplicateMatch = reconciliationMatcher.findMatch(
                userId, receipt.transactionDate(), receipt.amount().toBigDecimal(), receipt.merchantDomain());

        StagedRow row = new StagedRow(
                receipt.transactionDate(),
                descriptionFor(receipt),
                receipt.amount().toBigDecimal(),
                "EXPENSE",
                "Other",
                "default",
                null,
                duplicateMatch.isPresent(),
                null,
                null,
                duplicateMatch.orElse(null),
                RowKind.TRANSACTION,
                receipt.confidence());

        importSessionService.createSession(userId, fileNameFor(receipt), identity,
                List.of(row), unknownAccount(), null, ImportSession.SOURCE_GMAIL);
        return Result.STAGED;
    }

    /** {@code identity}, not the receipt HTML, is what {@code storeContent} persists as this
     *  session's "file" -- a 20-odd byte provenance marker, never the email's content. */
    private static String descriptionFor(ParsedReceipt receipt) {
        return receipt.merchantDomain();
    }

    /** Purely cosmetic — what the "Continue previous import" card list (file-upload-shaped UI,
     *  reused rather than replaced per §5.1) shows for a receipt instead of a filename. */
    private static String fileNameFor(ParsedReceipt receipt) {
        return receipt.merchantDomain() + " receipt — " + FILE_NAME_DATE.format(receipt.transactionDate());
    }

    /**
     * A receipt carries no bank/account signal at all — unlike a bank statement, there is no
     * column to read one from. This is the same "genuinely unknown" case {@code
     * DetectedAccountInfo} already has an honest answer for: {@code bank.id()="OTHER"} and {@code
     * detectedProduct="UNKNOWN"} with {@code productNeedsReview=true}, exactly as an unidentifiable
     * PDF section gets today. The user picks or creates the real account at confirm time — that
     * choice is session-level, not per-row, so a placeholder here does not block it.
     *
     * <p>{@code suggestedAccountType="SAVINGS"} matches {@code StatementValidator}'s own fallback
     * for the identical "nothing indicates otherwise" case — not a new guess invented here.
     */
    private static DetectedAccountInfo unknownAccount() {
        return new DetectedAccountInfo(
                "Gmail receipts", "SAVINGS",
                null, null, null, null,
                null, null, null,
                null, null, null,
                AccountDto.BankDto.from(BankRegistry.get(BankRegistry.UNKNOWN_ID)),
                "UNKNOWN", 0.0, true, List.of("Derived from a Gmail receipt, not a bank statement — no account context is available."),
                null,
                null, null, null, null, null, null, null);
    }
}
