package com.finora.dto;

import com.finora.accounts.AccountDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ImportDto {

    /**
     * A row that could NOT be parsed into a {@link StagedRow} -- surfaced to the user instead of
     * silently vanishing (see docs/engineering/financial-document-intelligence-principles.md's
     * "Never lose information" section). {@code raw} is the row exactly as extracted (whatever
     * columns the source format/layout produced, unchanged) so the user can see what the engine
     * actually saw; {@code reason} is {@link com.finora.imports.TransactionNormalizer#explainFailure}'s
     * human-readable explanation of why it didn't survive normalization. This is a diagnostic-only
     * record for review -- an unparseable row is never counted in {@code totalParsed} and is never
     * confirmable into the ledger; a user who wants that transaction has to re-enter it manually or
     * fix the source file and re-import.
     */
    public record UnparseableRow(Map<String, String> raw, String reason) {}

    /** One parsed CSV row, auto-categorized and ready for user review before commit.
     *
     *  referenceNumber/balanceAfter (Phase 1 "capture facts" — see
     *  docs/engineering/financial-document-intelligence-principles.md) are best-effort, nullable,
     *  and never guessed: referenceNumber is only set when the source row had a recognizable
     *  reference/cheque/instrument-ID column (see TransactionNormalizer.REFERENCE_HINTS);
     *  balanceAfter is only set when the row had a recognizable running-balance value (see
     *  TransactionNormalizer.BALANCE_HINTS) — distinct from amount, which is this transaction's
     *  own value, not the account's balance after it. */
    public record StagedRow(
            LocalDate date,
            String description,
            BigDecimal amount,
            String type,
            String suggestedCategory,
            String categorySource,   // "learned" | "rule" | "user_rule" | "global_rule" | "default" | "file"
            UUID ruleId,             // set only when categorySource is "user_rule" or "global_rule"
            boolean likelyDuplicate,
            String referenceNumber,  // best-effort, null when the source had no recognizable reference/cheque column
            BigDecimal balanceAfter  // best-effort, null when the source had no recognizable running-balance column
    ) {}

    /**
     * Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
     * the structural facts about one parsed document/section -- page/table/column counts and the
     * header list, split from {@link com.finora.imports.DocumentContext#buildFingerprint()} on
     * purpose so "have we seen this layout before" is an equality check against the fingerprint
     * string, not a JSON diff against this record. unknownHeaders is the subset of headers not
     * matched by any recognized hint list (see TransactionNormalizer.recognizedColumnNames()) --
     * this is the "never lose information" principle extended from rows to columns.
     */
    public record FinancialDocumentMetadata(
            String sourceFormat,       // "PDF" | "CSV"
            String parser,             // simple class name of the parser that produced this, e.g. "PdfPreviewGenerator"
            int pages,
            int tables,
            int columns,
            List<String> headers,
            List<String> unknownHeaders
    ) {}

    /**
     * One capability firing during a single parse run. status is always "SUCCESS" today -- the
     * pipeline only ever reports a capability that fired, not one attempted-but-failed -- kept as
     * an event shape (not a bare capability-name string) specifically so confidence/duration/
     * warnings/version can be added later without a schema change (see DocumentContext).
     */
    public record CapabilityActivation(String capability, String status) {}

    /**
     * Best-effort account-level fields pulled from the statement itself, alongside the parsed
     * transaction rows. Every field here is nullable and genuinely IS null when the file didn't
     * contain enough signal to derive it — none of these are guessed just to fill the field.
     * Account holder name genuinely does show up on real exports (see the SBI/PNB dummy
     * statements: an "Account Holder" column) — detected the same best-effort way as
     * accountNumberMasked, and null when the file doesn't carry it, in which case the account
     * holder really is just whoever's logged in.
     *
     * bank/suggestedName: the bank is detected from the filename and the file's own metadata
     * rows against com.finora.util.BankRegistry (see CsvImportService) — when recognized,
     * suggestedName is the bank's official name (e.g. "Punjab National Bank"), never the raw
     * filename. When no bank is recognized, bank.id() is "OTHER" and suggestedName falls back to
     * a clean generic label ("Bank Statement Import") -- the user is always free to rename
     * either way, but the review screen never shows parser/filename-derived text.
     */
    public record DetectedAccountInfo(
            String suggestedName,          // official bank name, or a clean generic fallback -- never a raw filename
            String suggestedAccountType,   // best-effort guess ("CREDIT_CARD" vs "SAVINGS") from column signals
            BigDecimal openingBalance,     // derived from a running-balance column, if the file has one
            BigDecimal closingBalance,
            LocalDate statementPeriodStart,
            LocalDate statementPeriodEnd,
            String accountNumberMasked,    // only set if an account/card-number-like column was present
            BigDecimal creditLimit,        // only set if a credit-limit column was present
            LocalDate paymentDueDate,      // only set if a due-date column was present
            String accountHolderName,      // only set if an account-holder-like column was present
            String branchName,             // only set if a branch-name-like column was present
            String ifscCode,               // only set if an IFSC-like column was present
            AccountDto.BankDto bank,       // resolved bank metadata (name/color/initials); id "OTHER" if undetected

            // --- Financial Product Discovery (com.finora.imports.product) --------------------------
            // What this section actually IS, as opposed to what account to prefill. The two are
            // different questions: suggestedAccountType above has always had to name SOMETHING for
            // the review form, while detectedProduct is allowed to say UNKNOWN -- which is a
            // successful outcome, not a failure. A section the engine cannot identify is shown to
            // the user to name once; it is never guessed into an account, because a wrong product
            // silently writes wrong data into someone's net worth.
            String detectedProduct,        // FinancialProductType name, e.g. "SAVINGS", "FIXED_DEPOSIT", "UNKNOWN"
            double productConfidence,      // 0..0.95
            boolean productNeedsReview,    // true unless the product was identified, proved itself, and is modelled
            List<String> productEvidence,  // the reasoning, so a wrong answer can be argued with

            // A one-way hash of institution + this product's own full number, computed at STAGING
            // because that is the only point the full number exists -- it is hashed there and
            // discarded, so the number never reaches a session, a database column or a log. Lets
            // next month's statement recognise the same deposit instead of creating a second one
            // and double-counting it. Null when the document gave no usable number.
            String productIdentityHash,

            // What makes a deposit a DEPOSIT rather than a name and a balance -- see
            // com.finora.imports.product.ProductAttributes for the full reasoning. All seven
            // nullable; a field not relevant to this product's type is simply never populated (a
            // fixed deposit has no installmentAmount; a recurring deposit has no principalAmount).
            BigDecimal principalAmount,
            BigDecimal interestRate,
            LocalDate maturityDate,
            BigDecimal maturityAmount,
            BigDecimal installmentAmount,
            Integer installmentsPaid,
            Integer installmentsTotal
    ) {}

    public record StagingResponse(List<StagedRow> rows, int totalParsed, int flaggedDuplicates,
                                   DetectedAccountInfo detectedAccount, List<UnparseableRow> unparseableRows) {}

    /** One detected account section within a single PDF upload -- e.g. HSBC's "Composite
     *  Statement" bundles a savings-account section and a credit-card section in one file, each
     *  of which becomes one of these. Structurally identical to {@link StagingResponse}; kept as
     *  its own type rather than reused directly so a multi-section response's shape
     *  ({@link PdfStagingSessionResponse}) reads unambiguously as "a list of these," not "a list
     *  of the same type used for the single-account case," which would invite confusing the two. */
    public record StagedAccountSection(DetectedAccountInfo detectedAccount, List<StagedRow> rows,
                                        int totalParsed, int flaggedDuplicates, List<UnparseableRow> unparseableRows) {}

    /** Response shape for POST /import/pdf/stage. Exactly one of {@code staging}/{@code sections}
     *  is populated, selected by {@code multiAccount}: a PDF with one detected account section
     *  (the common case, and the only case CSV upload can ever produce) returns the same
     *  {@code staging} shape the endpoint always has, unchanged; a PDF with more than one section
     *  detected (e.g. HSBC's composite statement) returns {@code sections} instead, and the
     *  frontend review screen needs a per-section "Account 1 of N" UI to review/confirm each one
     *  before posting {@link MultiAccountConfirmRequest}. */
    public record PdfStagingSessionResponse(UUID sessionId, boolean multiAccount,
                                             StagingResponse staging, List<StagedAccountSection> sections) {}

    /** One account's worth of reviewed rows within a {@link MultiAccountConfirmRequest} --
     *  structurally identical to {@link ConfirmRequest} minus the sessionId (shared once at the
     *  top level, not repeated per section). */
    public record SectionConfirm(
            List<ConfirmedRow> rows, UUID existingAccountId, NewAccountRequest newAccount,
            BigDecimal statementOpeningBalance, BigDecimal statementClosingBalance
    ) {}

    /** Confirms every section of a multi-account PDF staging session together -- see
     *  ImportService.confirmMultiSection(), which loops calling the existing single-account
     *  confirm() once per section rather than duplicating that logic here. */
    public record MultiAccountConfirmRequest(UUID sessionId, List<SectionConfirm> sections) {}

    public record MultiAccountConfirmResponse(List<ConfirmResponse> perAccount) {}

    /** Wraps StagingResponse with the persisted session id (ADR-0002) -- returned by the public
     *  /import/csv/stage endpoint specifically. StagingResponse itself is left untouched (no
     *  sessionId field) so the internal byte-stream parseAndStage() overload used by
     *  StatementImportService's reimport flow (which has no session concept -- it's replaying an
     *  already-stored file, not a fresh upload that could be interrupted) doesn't need to change. */
    public record StagingSessionResponse(UUID sessionId, StagingResponse staging) {}

    /** One entry in "your unfinished imports" -- GET /import/sessions. Deliberately doesn't
     *  include the full staged rows (that's a second call, GET /import/sessions/{id}, once the
     *  user picks one to resume) -- this is just enough to render a list. */
    public record ImportSessionSummaryDto(
            UUID id, String fileName, int rowCount, java.time.Instant createdAt, java.time.Instant expiresAt
    ) {}

    /** What the frontend sends back after the user reviews/edits staged rows. A statement import
     *  is for exactly one account — either an existing one (existingAccountId) or a new one
     *  Finora should create from the reviewed/edited detection (newAccount). Exactly one of the
     *  two should be set.
     *
     *  sessionId (ADR-0002) replaces re-uploading the original file at confirm time -- the file's
     *  bytes are already persisted on the ImportSession from staging, so confirm() looks them up
     *  rather than requiring the multipart upload a second time.
     *
     *  statementOpeningBalance/statementClosingBalance are echoed from staging's detection
     *  regardless of which account this goes into — they're purely for the Statement History
     *  record (StatementImport), not the live account balance, which is only ever set at
     *  account-creation time from newAccount.openingBalance(). */
    public record ConfirmRequest(
            UUID sessionId,
            List<ConfirmedRow> rows, UUID existingAccountId, NewAccountRequest newAccount,
            BigDecimal statementOpeningBalance, BigDecimal statementClosingBalance
    ) {}

    /**
     * @param detectedProduct      the FinancialProductType the review screen is confirming, echoed
     *                             back from staging. Null from an older client, which then behaves
     *                             exactly as before.
     * @param productIdentityHash  echoed back from {@link DetectedAccountInfo}, so confirm can tell
     *                             "the deposit I already hold" from "a new one". Already a hash
     *                             when it reaches the client -- no unmasked number ever leaves the
     *                             server, so a client cannot forge one into a different product's
     *                             identity without already knowing that product's full number.
     */
    public record NewAccountRequest(
            String name, String accountType, BigDecimal openingBalance, BigDecimal creditLimit, LocalDate dueDate,
            String accountHolderName, String accountNumberMasked, String bankId,
            String branchName, String ifscCode,
            String detectedProduct, String productIdentityHash,
            // Echoed back unchanged from DetectedAccountInfo, same round-trip as detectedProduct
            // above -- these are server-detected values the review screen displays read-only, not
            // something the user edits, so there is nothing here for a client to have gotten wrong.
            BigDecimal principalAmount, BigDecimal interestRate, LocalDate maturityDate,
            BigDecimal maturityAmount, BigDecimal installmentAmount,
            Integer installmentsPaid, Integer installmentsTotal
    ) {}

    public record ConfirmedRow(
            LocalDate date, String description, BigDecimal amount, String type,
            String category, boolean include,
            String categorySource,   // "learned" | "rule" | "user_rule" | "global_rule" | "default" | "file" — carried from staging
            UUID ruleId,             // carried from staging — see StagedRow.ruleId
            boolean likelyDuplicate, // carried from staging, so the summary can report it honestly
            String referenceNumber,  // carried from staging — see StagedRow.referenceNumber
            BigDecimal balanceAfter  // carried from staging — see StagedRow.balanceAfter
    ) {}

    /** Everything the PRD's "Import Summary" step asks for, computed from what actually happened
     *  during this import — not placeholder counts. See CsvImportService.confirm().
     *
     *  account is the account this import went into, as it stands right after this import (so
     *  the summary screen can show the bank logo/official name/holder/masked number/current
     *  balance without a second round-trip). totalCredits/totalDebits are summed only over rows
     *  actually imported (skipped/unchecked rows don't count). source is always "CSV" today —
     *  see CsvImportService's class comment on why PDF/Gmail import isn't implemented yet; this
     *  field exists now so the summary UI doesn't need to change when they are. */
    public record ConfirmResponse(
            int imported,
            int skipped,
            int duplicatesDetected,
            int transfersIdentified,
            int newMerchantsLearned,
            List<String> accountsCreated,
            // What was created, counted by PRODUCT rather than by account -- so the summary can say
            // "1 Savings, 1 Fixed Deposit" instead of "3 accounts", which for a combined statement
            // was both less informative and wrong: two of those three were never accounts.
            Map<String, Integer> productsCreated,
            Map<String, Integer> categoriesAssigned,
            List<String> warnings,
            AccountDto account,
            BigDecimal totalCredits,
            BigDecimal totalDebits,
            BigDecimal statementOpeningBalance,
            BigDecimal statementClosingBalance,
            LocalDate statementPeriodStart,
            LocalDate statementPeriodEnd,
            long importDurationMs,
            String source
    ) {}
}
