package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GRID_METADATA_TRAILING_LABEL: an account-details grid where each row's VALUE comes BEFORE its
 * own label on the same line ("317002010038811 Account Number", "UBIN0531707 IFSC"), the reverse
 * of the ordinary "Label: Value" shape {@code ACCOUNT_HOLDER}/{@code ACCOUNT_NUMBER}/{@code IFSC}
 * already handle. Modeled on a real Union Bank of India statement (see
 * {@code PdfMetadataExtractor}'s own doc comments for the exact lines and reasoning), but the
 * underlying capability -- a metadata grid whose values precede their labels -- isn't specific to
 * that bank.
 */
class PdfMetadataExtractorTest {

    private final PdfMetadataExtractor extractor = new PdfMetadataExtractor();

    @Test
    void extract_stillHandlesTheOrdinaryLabelThenValueShape_unaffectedByTheNewFallbacks() {
        var metadata = extractor.extract(List.of(
                "Account Holder Name: JOHN DOE",
                "Account Number: 000123456789",
                "Branch Name: MG ROAD BRANCH",
                "IFSC: SBIN0001234"));

        assertThat(metadata.accountHolderName()).isEqualTo("JOHN DOE");
        assertThat(metadata.accountNumberMasked()).endsWith("6789");
        assertThat(metadata.branchName()).isEqualTo("MG ROAD BRANCH");
        assertThat(metadata.ifscCode()).isEqualTo("SBIN0001234");
    }

    @Test
    void extract_recognizesAnAccountNumber_whenTheValuePrecedesItsLabelOnTheSameLine() {
        var metadata = extractor.extract(List.of("317002010038811 Account Number"));

        assertThat(metadata.accountNumberMasked()).endsWith("8811");
    }

    @Test
    void extract_recognizesAnIfscCode_byItsDistinctiveShape_evenMergedWithAnUnrelatedField() {
        // The real statement's IFSC line is merged with an unrelated Email field by the time it
        // reaches this class (both landed on the same extracted line) -- IFSC's fixed shape (4
        // letters, a literal 0, 6 more alphanumerics) is found directly, independent of any label
        // or of what else shares its line.
        var metadata = extractor.extract(List.of("M***************0@GMAIL.COM Email id UBIN0531707 IFSC"));

        assertThat(metadata.ifscCode()).isEqualTo("UBIN0531707");
    }

    @Test
    void extract_recognizesAnAccountHolderName_fromAnAccountNameFieldEndingTheLine() {
        // The preceding "3,BEHIND" is itself capitalized ("BEHIND") -- the 3-word cap on the
        // captured name is what keeps it out of the result.
        var metadata = extractor.extract(List.of(
                "58 ROAD NO 3,BEHIND  SHIVANI SURESH MOURYA Account Name"));

        assertThat(metadata.accountHolderName()).isEqualTo("SHIVANI SURESH MOURYA");
    }

    @Test
    void extract_recognizesAStatementPeriod_whenTheDatesPrecedeTheLabelOnTheSameLine() {
        var metadata = extractor.extract(List.of("01-05-2026 to 31-07-2026 Statement Period"));

        assertThat(metadata.statementPeriodStart()).isEqualTo(java.time.LocalDate.of(2026, 5, 1));
        assertThat(metadata.statementPeriodEnd()).isEqualTo(java.time.LocalDate.of(2026, 7, 31));
    }

    @Test
    void extract_doesNotMisreadATwoColumnSectionHeader_asABranchNameField() {
        // "Branch Address" here is a section header ("Branch Address" | "Statement Details" side
        // by side), not a genuine "Branch: <name>" line -- a real regression found against the
        // same statement: the bare "Branch" match used to consume "Address Statement Details" as
        // if it were the branch name.
        var metadata = extractor.extract(List.of("Branch Address Statement Details"));

        assertThat(metadata.branchName()).isNull();
    }
}
