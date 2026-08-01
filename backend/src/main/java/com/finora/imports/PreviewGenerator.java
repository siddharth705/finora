package com.finora.imports;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
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

    public PreviewGenerator(CsvParser csvParser, TransactionNormalizer transactionNormalizer,
                             StatementValidator statementValidator) {
        this.csvParser = csvParser;
        this.transactionNormalizer = transactionNormalizer;
        this.statementValidator = statementValidator;
    }

    public StagingResponse generate(UUID userId, String filename, InputStream contentStream) throws IOException {
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
            return new StagingResponse(staged, 0, 0, empty, unparseable);
        }
        String[] headerRow = allRows.get(headerIdx);

        for (int i = headerIdx + 1; i < allRows.size(); i++) {
            String[] cells = allRows.get(i);
            if (csvParser.isBlankRow(cells)) continue;

            Map<String, String> row = csvParser.zipRow(headerRow, cells);

            StagedRow parsed = transactionNormalizer.normalize(userId, row);
            if (parsed != null) {
                staged.add(parsed);
            } else {
                unparseable.add(new UnparseableRow(row, transactionNormalizer.explainFailure(row)));
            }

            // Account-level signals are scanned on every row regardless of whether it parsed as
            // a transaction — see StatementValidator.scanRow.
            statementValidator.scanRow(row, parsed, signals);
        }

        int dupCount = (int) staged.stream().filter(StagedRow::likelyDuplicate).count();
        DetectedAccountInfo detected = statementValidator.buildDetectedAccountInfo(filename, allRows, headerIdx, staged, signals);
        return new StagingResponse(staged, staged.size(), dupCount, detected, unparseable);
    }
}
