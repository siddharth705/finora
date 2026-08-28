package com.finora.imports;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.CategoryRule;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.ImportDto.UnparseableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the staged preview a user reviews before confirming an import: parses the file
 * (CsvParser), normalizes each row into a transaction candidate (TransactionNormalizer), detects
 * account/bank-level signals alongside it (StatementValidator), and assembles the result. Nothing
 * here writes to the database — see {@link ImportService#confirm} for the commit step.
 */
@Component
public class PreviewGenerator {

    private final CsvParser csvParser;
    private final TransactionNormalizer transactionNormalizer;
    private final StatementValidator statementValidator;

    private final ImportVerifier importVerifier;
    private final com.finora.service.RuleEngineService ruleEngineService;

    public PreviewGenerator(CsvParser csvParser, TransactionNormalizer transactionNormalizer,
                             StatementValidator statementValidator,
                             ImportVerifier importVerifier,
                             com.finora.service.RuleEngineService ruleEngineService) {
        this.csvParser = csvParser;
        this.transactionNormalizer = transactionNormalizer;
        this.statementValidator = statementValidator;
        this.importVerifier = importVerifier;
        this.ruleEngineService = ruleEngineService;
    }

    public StagingResponse generate(UUID userId, String filename, InputStream contentStream) throws IOException {
        return generateWithContext(userId, filename, contentStream).response();
    }

    /** One {@link DocumentContext}'s worth of recorded structural facts and capability
     *  activations for this CSV file (Phase 1 "capture facts" -- see
     *  docs/engineering/financial-document-intelligence-principles.md), alongside the same
     *  {@link StagingResponse} {@link #generate} has always returned. */
    public record CsvGenerationResult(StagingResponse response, DocumentContext documentContext) {}

    /** Same as {@link #generate}, but also returns the {@link DocumentContext} built while
     *  parsing -- the entry point {@code ImportService} uses when it needs to persist that
     *  context (a fresh CSV upload); {@link #generate} stays the plain, context-discarding
     *  wrapper every existing caller already depends on. */
    public CsvGenerationResult generateWithContext(UUID userId, String filename, InputStream contentStream) throws IOException {
        DocumentContext ctx = new DocumentContext("CSV", "PreviewGenerator");
        List<StagedRow> staged = new ArrayList<>();
        // "Never lose information" (see the engineering principles doc) -- a row that fails to
        // normalize is reported with WHY, not just silently absent from the row count. A blank
        // line is NOT reported here (see the loop below) since it was never a candidate
        // transaction in the first place, just formatting noise a real export routinely has.
        List<UnparseableRow> unparseable = new ArrayList<>();
        StatementValidator.AccountSignalAccumulator signals = new StatementValidator.AccountSignalAccumulator();

        List<String[]> allRows = csvParser.readAll(contentStream);

        int headerIdx = csvParser.findHeaderRowIndex(allRows);
        if (headerIdx < 0) {
            // No recognizable header anywhere — nothing to stage, but still return a well-formed
            // (empty) response rather than letting a downstream NPE surface as a 500. Previously
            // this returned an empty unparseableRows list too -- indistinguishable from a
            // genuinely empty upload. "Never lose information" applies at the whole-file level
            // just as much as the per-row level: every non-blank line the file actually contains
            // is surfaced here, column-indexed since there's no recognized header to key by.
            for (String[] cells : allRows) {
                if (csvParser.isBlankRow(cells)) continue;
                Map<String, String> raw = new LinkedHashMap<>();
                for (int c = 0; c < cells.length; c++) raw.put("column " + (c + 1), cells[c]);
                unparseable.add(new UnparseableRow(raw, "No column header row was recognized anywhere in this file"));
            }
            DetectedAccountInfo empty = statementValidator.buildDetectedAccountInfo(filename, allRows, -1, staged, signals);
            ctx.recordUnparseable(unparseable);
            return new CsvGenerationResult(new StagingResponse(staged, 0, 0, empty, unparseable), ctx);
        }
        String[] headerRow = allRows.get(headerIdx);
        ctx.recordTables(1);
        ctx.recordHeaders(List.of(headerRow));

        // Loaded once for the whole statement, not once per row. The per-row overload re-queried
        // category_rules twice for every row, always returning the same two result sets -- 2.00
        // queries/row measured, the largest single N+1 in this pipeline. A user's rules cannot
        // change partway through parsing one file, so hoisting is equivalent by construction.
        List<CategoryRule> rules = ruleEngineService.ruleSet(userId);
        // Built once per statement for the same reason the rule set is: a user's existing
        // transactions cannot change partway through parsing one file.
        DuplicateIndex duplicateIndex = transactionNormalizer.duplicateIndexFor(userId);
        // Same reasoning again, for merchant resolution (Transaction Intelligence Phase A): staging
        // has no enclosing transaction, so MerchantNormalizationEngine.resolveReadOnly's own
        // per-transaction memo cannot help here -- see MerchantIndex's own doc comment.
        MerchantIndex merchantIndex = transactionNormalizer.merchantIndexFor(userId);

        for (int i = headerIdx + 1; i < allRows.size(); i++) {
            String[] cells = allRows.get(i);
            if (csvParser.isBlankRow(cells)) continue;

            Map<String, String> row = csvParser.zipRow(headerRow, cells);

            StagedRow parsed = transactionNormalizer.normalize(userId, row, ctx, rules, duplicateIndex, merchantIndex);
            // 1-based, relative to the first data row -- the position an operator reviewing this
            // import in the Import Explorer would count rows by, not the raw file line number
            // (which would also count the header and any rows above it).
            int rowPosition = i - headerIdx;
            if (parsed != null) {
                parsed = parsed.withRowPosition(rowPosition);
                // RowKind.BALANCE_MARKER rows (see that enum's doc comment) are excluded from
                // `staged` -- the same fix, and the same reasoning, as PdfPreviewGenerator's
                // ledger-section loop: a structural balance-only row must never become an
                // importable transaction candidate. `parsed` is still passed to scanRow below
                // regardless of kind, so its date/amount/balance still feed opening/closing
                // balance derivation -- nothing about that derivation depended on the row being
                // staged as a transaction.
                if (parsed.kind() == RowKind.TRANSACTION) {
                    staged.add(parsed);
                } else if (transactionNormalizer.hasUnrecognizedNonBlankColumn(row)) {
                    // Not a CONFIDENT balance-marker classification -- see
                    // TransactionNormalizer.hasUnrecognizedNonBlankColumn's own doc comment and
                    // PdfPreviewGenerator's identical guard. A row with a non-blank value under a
                    // column name this class doesn't recognize at all must not silently vanish
                    // from `staged` just because that same gap made it classify as
                    // BALANCE_MARKER; route it to the existing unparseable diagnostic instead.
                    // `parsed` is still passed to scanRow below regardless, so balance derivation
                    // is unaffected.
                    unparseable.add(new UnparseableRow(row,
                            "Row has a value in an unrecognized column and no recognized transactional "
                                    + "amount column, so it could not be confidently classified as a transaction "
                                    + "or excluded as a balance marker"));
                } else if (transactionNormalizer.hasUnparseableRecognizedAmount(row)) {
                    // Third fix pass -- see TransactionNormalizer.hasUnparseableRecognizedAmount's
                    // own doc comment. This row's column NAMES are all recognized, but a real
                    // transactional column (Debit/Credit/Amount/etc.) holds a non-blank value that
                    // CsvParser.parseNumeric still couldn't parse (e.g. "1500/-", or a bank Dr/Cr
                    // format variant not yet covered) -- that value never resolves, so the row
                    // classified BALANCE_MARKER purely because its real amount column came up
                    // empty, not because it genuinely lacks transactional data. Excluding it
                    // unconditionally would silently vanish it with zero trace. Route it to the
                    // unparseable diagnostic instead, same as the unrecognized-column case above.
                    unparseable.add(new UnparseableRow(row,
                            "Row has a value in a recognized transactional amount column that could not be "
                                    + "parsed as a number, so it could not be confidently classified as a "
                                    + "transaction or excluded as a balance marker"));
                }
            } else {
                unparseable.add(new UnparseableRow(row, transactionNormalizer.explainFailure(row)));
            }

            // Account-level signals are scanned on every row regardless of whether it parsed as
            // a transaction — see StatementValidator.scanRow.
            statementValidator.scanRow(row, parsed, signals);
        }

        int dupCount = (int) staged.stream().filter(StagedRow::likelyDuplicate).count();
        DetectedAccountInfo detected = statementValidator.buildDetectedAccountInfo(filename, allRows, headerIdx, staged, signals);
        ctx.recordUnparseable(unparseable);
        // Cross-checks the parsed rows against the statement's own running balance. Reported, never
        // enforced -- see BalanceChainValidator for why refusing an import would be the wrong trade.
        var verification = importVerifier.verify(staged,
                detected == null ? null : detected.openingBalance(),
                detected == null ? null : detected.closingBalance());
        return new CsvGenerationResult(
                new StagingResponse(staged, staged.size(), dupCount, detected, unparseable, verification), ctx);
    }
}
