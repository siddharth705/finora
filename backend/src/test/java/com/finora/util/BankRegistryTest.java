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

    @ParameterizedTest
    @CsvSource({
            // Every one of these is ordinary statement text that used to detect a bank, because
            // alias matching ran over text with the separators stripped out -- so an alias could
            // start mid-word and end mid-word. The comment shows where the false match landed.
            "Rewards Bill,                 REWARD[SBI]LL -> State Bank of India",
            "ATM Balance,                  A[TMB]ALANCE -> Tamilnad Mercantile Bank",
            "Citizen of India,             [CITI]ZEN -> Citibank",
            "Goods Bill Payment,           GOOD[SBI]LL -> State Bank of India",
    })
    void ordinaryStatementText_thatMerelyCONTAINSAnAliasMidWord_detectsNoBank(String line, String whyItUsedToFail) {
        assertThat(BankRegistry.detect("statement.pdf", List.of(line)).id())
                .as(whyItUsedToFail)
                .isEqualTo(BankRegistry.UNKNOWN_ID);
    }

    @ParameterizedTest
    @CsvSource({
            "Bank of Baroda,        BOB",
            "BANK OF BARODA,        BOB",
            "HDFC BANK LIMITED,     HDFC",
            "State Bank of India,   SBI",
            "Kotak Mahindra Bank,   KOTAK",
    })
    void aBankNameIsStillMatchedHoweverTheBankChoseToSpaceIt(String letterhead, String expectedId) {
        // The flip side of the test above: anchoring to word boundaries must not cost us the
        // ability to match an alias registered separator-free ("BANKOFBARODA") against a name the
        // bank prints with spaces. The alias has to cover whole words -- not each word alone.
        assertThat(BankRegistry.detect("statement.pdf", List.of(letterhead)).id()).isEqualTo(expectedId);
    }

    @Test
    void theAccountsOwnLabelledIfsc_outranksABankNameAppearingElsewhereInTheDocument() {
        // A merchant's UPI handle carries a bank name into the narration of someone else's
        // statement. The letterhead is absent (a very common single-page export), so the only
        // brand word in the document belongs to the wrong bank. The labelled IFSC is the
        // account's own and settles it.
        BankRegistry.BankInfo bank = BankRegistry.detect("statement.pdf", List.of(
                "RTGS/NEFT IFSC : HDFC0XXXXXX   MICR : XXXXXXXXX",
                "UPI-VYAPAR.XXXXXXXXXXXX@ICICIBANK-ICIC0XXXXXX-XXXXXXXXXXXX-PAYMENT FROM PHONE"));

        assertThat(bank.id()).isEqualTo("HDFC");
    }

    @Test
    void counterpartyIfscCodesInsideNarrations_doNotOutvoteTheStatementsOwnBank() {
        // Indian UPI/NEFT narrations embed the COUNTERPARTY's IFSC, so a single statement
        // legitimately contains several banks' codes -- and they routinely outnumber the account's
        // own. Frequency can't resolve this; only the "IFSC" label can. Here no code is labelled,
        // so the unlabelled codes disagree, contribute nothing, and the letterhead decides.
        BankRegistry.BankInfo bank = BankRegistry.detect("statement.pdf", List.of(
                "HDFC BANK LIMITED",
                "UPI-EXAMPLE GENERAL STORES-PAYTMQR-YESB0XXXXXX-XXXXXXXXXXXX-PAYMENT FROM PHONE",
                "UPI-EXAMPLE TEA STALL-QXXXXXXXXX@YBL-YESB0YYYYYY-XXXXXXXXXXXX-PAYMENT",
                "NEFT CR-ICIC0XXXXXX-EXAMPLE TECHNOLOGY PRIVATE LIMITED CLIENT ACCO"));

        assertThat(bank.id()).isEqualTo("HDFC");
    }

    @Test
    void anUnlabelledIfsc_isStillUsedWhenNothingContradictsIt() {
        BankRegistry.BankInfo bank = BankRegistry.detect("statement.pdf", List.of(
                "Account No : XXXXXXXXXXXXXX", "UTIB0XXXXXX", "Branch : Example Road"));

        assertThat(bank.id()).isEqualTo("AXIS");
    }

    @Test
    void twoBanksWithEquallyStrongEvidence_fallBackToOtherRatherThanPickingOne() {
        // Registration order used to decide this silently -- public-sector banks are registered
        // first, so SBI beat HDFC on nothing more than being declared earlier in the file. An
        // unjustifiable answer is worse than OTHER, which the review screen asks the user to fix.
        BankRegistry.BankInfo bank = BankRegistry.detect("statement.pdf", List.of(
                "State Bank of India", "HDFC BANK LIMITED"));

        assertThat(bank.id()).isEqualTo(BankRegistry.UNKNOWN_ID);
    }

    @Test
    void aRepeatedLetterhead_outweighsASingleStrayMentionOfAnotherBank() {
        BankRegistry.BankInfo bank = BankRegistry.detect("statement.pdf", List.of(
                "HDFC BANK LIMITED", "Page 1", "HDFC BANK LIMITED", "Page 2",
                "NEFT transfer to ICICI Bank account"));

        assertThat(bank.id()).isEqualTo("HDFC");
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
