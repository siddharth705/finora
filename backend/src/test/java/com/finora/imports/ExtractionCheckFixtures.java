package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.exception.ApiException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The two {@link StagingResponse} shapes every direct {@link ExtractionCheck} test needs, plus the
 * catch-and-return helper for asserting on the rejection. Shared rather than each test class
 * carrying its own copy -- {@code ImageOnlyDocumentTest} and {@code ExplicitZeroActivityRejectionTest}
 * both used to declare byte-for-byte identical private copies of these three members, meaning
 * {@code StagingResponse}'s constructor shape was hardcoded in two places that had to be kept in
 * sync by hand.
 */
final class ExtractionCheckFixtures {

    private ExtractionCheckFixtures() {
    }

    static StagingResponse empty() {
        return new StagingResponse(List.of(), 0, 0, null, List.of(), null);
    }

    static StagingResponse withRows() {
        return new StagingResponse(
                List.of(new StagedRow(
                        LocalDate.of(2026, 6, 5), "SALARY CREDIT", new BigDecimal("55000.00"),
                        "INCOME", "Other", "default", null, false, null, null)),
                1, 0, null, List.of(), null);
    }

    /** Fails loudly rather than returning null, so a test that expected a rejection and didn't get
     *  one fails with a clear message instead of a later NPE. */
    static ApiException catchRejection(DocumentContext ctx) {
        try {
            ExtractionCheck.rejectIfNothingWasExtracted(empty(), ctx);
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected a rejection, and nothing was thrown");
    }
}
