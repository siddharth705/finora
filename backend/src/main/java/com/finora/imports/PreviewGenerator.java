package com.finora.imports;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
        StatementValidator.AccountSignalAccumulator signals = new StatementValidator.AccountSignalAccumulator();

        List<String[]> allRows = csvParser.readAll(contentStream);

        int headerIdx = csvParser.findHeaderRowIndex(allRows);
        if (headerIdx < 0) {
            // No recognizable header anywhere — nothing to stage, but still return a well-formed
            // (empty) response rather than letting a downstream NPE surface as a 500.
            DetectedAccountInfo empty = statementValidator.buildDetectedAccountInfo(filename, allRows, -1, staged, signals);
            return new StagingResponse(staged, 0, 0, empty);
        }
        String[] headerRow = allRows.get(headerIdx);

        for (int i = headerIdx + 1; i < allRows.size(); i++) {
            String[] cells = allRows.get(i);
            if (csvParser.isBlankRow(cells)) continue;

            Map<String, String> row = csvParser.zipRow(headerRow, cells);

            StagedRow parsed = transactionNormalizer.normalize(userId, row);
            if (parsed != null) staged.add(parsed);

            // Account-level signals are scanned on every row regardless of whether it parsed as
            // a transaction — see StatementValidator.scanRow.
            statementValidator.scanRow(row, parsed, signals);
        }

        int dupCount = (int) staged.stream().filter(StagedRow::likelyDuplicate).count();
        DetectedAccountInfo detected = statementValidator.buildDetectedAccountInfo(filename, allRows, headerIdx, staged, signals);
        return new StagingResponse(staged, staged.size(), dupCount, detected);
    }
}
