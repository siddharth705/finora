package com.finora.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the exact bug this registry was built to fix: an account card showing a raw,
 * title-cased filename ("Pnbone Stmt Xx4802 23072026") instead of the bank's real name. detect()
 * is what StatementValidator now calls instead of the old suggestNameFromFilename() -- these tests
 * cover the reported filename verbatim, plus every other bank the registry recognizes, plus the
 * "never guess, fall back honestly" case for anything unrecognized.
 */
class BankRegistryTest {

    @Test
    void theExactReportedFilename_isDetectedAsPunjabNationalBank() {
        BankRegistry.BankInfo bank = BankRegistry.detect("PNBONE_STMT_XX4802_23072026.csv", List.of());

        assertThat(bank.id()).isEqualTo("PNB");
        assertThat(bank.officialName()).isEqualTo("Punjab National Bank");
    }

    @ParameterizedTest
    @CsvSource({
            "HDFC_Statement_March.csv, HDFC, HDFC Bank",
            "sbi-savings-export.csv, SBI, State Bank of India",
            "icici_creditcard.csv, ICICI, ICICI Bank",
            "AXIS_BANK_STMT.csv, AXIS, Axis Bank",
            "kotak-mahindra-txns.csv, KOTAK, Kotak Mahindra Bank",
            "yesbank_statement.csv, YES, Yes Bank",
            "idfcfirst_export.csv, IDFC, IDFC FIRST Bank",
            "indusind_txns.csv, INDUSIND, IndusInd Bank",
    })
    void recognizedBankFilenames_resolveToTheCorrectOfficialName(String filename, String expectedId, String expectedName) {
        BankRegistry.BankInfo bank = BankRegistry.detect(filename, List.of());

        assertThat(bank.id()).isEqualTo(expectedId);
        assertThat(bank.officialName()).isEqualTo(expectedName);
    }

    @Test
    void anUnrecognizedFilename_fallsBackToOtherRatherThanGuessing() {
        BankRegistry.BankInfo bank = BankRegistry.detect("random_export_2024.csv", List.of());

        assertThat(bank.id()).isEqualTo(BankRegistry.UNKNOWN_ID);
        assertThat(bank.officialName()).isNull();
    }

    @Test
    void aNullOrBlankFilename_doesNotThrow_andFallsBackToOther() {
        assertThat(BankRegistry.detect(null, List.of()).id()).isEqualTo(BankRegistry.UNKNOWN_ID);
        assertThat(BankRegistry.detect("", List.of()).id()).isEqualTo(BankRegistry.UNKNOWN_ID);
    }

    @Test
    void aBankNamePresentOnlyInTheStatementsOwnMetadataRows_isStillDetected() {
        // Simulates a real export where the filename is generic but the letterhead/metadata
        // rows above the transaction table carry the bank's name -- collectBankTextHints in
        // StatementValidator is what supplies these in practice.
        BankRegistry.BankInfo bank = BankRegistry.detect(
                "statement.csv", List.of("Customer Statement", "State Bank of India", "Branch: MG Road"));

        assertThat(bank.id()).isEqualTo("SBI");
    }

    @Test
    void get_resolvesAKnownId() {
        assertThat(BankRegistry.get("PNB").officialName()).isEqualTo("Punjab National Bank");
    }

    @Test
    void get_neverThrowsForAnUnrecognizedOrNullId_fallingBackToOther() {
        assertThat(BankRegistry.get("NOT_A_REAL_BANK").id()).isEqualTo(BankRegistry.UNKNOWN_ID);
        assertThat(BankRegistry.get(null).id()).isEqualTo(BankRegistry.UNKNOWN_ID);
    }

    @Test
    void all_neverIncludesTheOtherFallbackEntry() {
        assertThat(BankRegistry.all()).noneMatch(b -> BankRegistry.UNKNOWN_ID.equals(b.id()));
        assertThat(BankRegistry.all()).isNotEmpty();
    }
}
