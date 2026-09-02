package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonToPersonTransferDetectorTest {

    @Test
    void detectsUpiTransferToNamedIndividual() {
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-sampleuser@ybl-REF881234"))
                .isTrue();
    }

    @Test
    void detectsNeftTransferWithASingleLetterInitial() {
        // A real, common Indian-naming-convention shape ("R BAGAVATHI SHANKAR") -- a single-letter
        // initial does not by itself disqualify the segment as a name.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "NEFT-R BAGAVATHI SHANKAR-HDFC0XXXXXX-TRANSFER"))
                .isTrue();
    }

    @Test
    void doesNotFireWithoutATransferMarker() {
        // A person-shaped word with no UPI/NEFT/IMPS/RTGS context is too weak a signal on its
        // own -- it could just as easily be part of a merchant's trade name.
        //
        // Carries NO business-suffix token AND no numeric token: two earlier versions of this test
        // were still non-binding. "...ENTERPRISES..." was rejected by containsBusinessSignal, and
        // "...INVOICE 4471" by NAME_TOKEN failing on "4471" -- so in both cases deleting the
        // transfer-marker gate entirely left the test green. Every segment here would pass
        // looksLikePersonName, so the gate is genuinely the only thing that can reject it.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "RAJESH KUMAR INVOICE"))
                .isFalse();
    }

    @Test
    void excludesBusinessSuffixToken() {
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-SHARMA TRADERS-sampleuser@ybl-REF881234"))
                .isFalse();
    }

    @Test
    void globalBusinessSignalExcludesEvenWhenAnotherSegmentLooksLikeAName() {
        // A business signal ANYWHERE in the narration disqualifies the whole line, even if an
        // unrelated segment elsewhere happens to look name-shaped -- a business's legal name can
        // itself be built from a person's name ("Rajesh Kumar Sharma Traders Pvt Ltd").
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-SHARMA TRADERS PVT LTD-REF881234"))
                .isFalse();
    }

    @Test
    void excludesMerchantQrVpaHandle() {
        // Carries a genuinely person-shaped segment ("RAJESH KUMAR") alongside the merchant-QR
        // VPA, so only the QR check can reject it. An earlier version used a narration whose every
        // segment already failed the name-shape test, and so passed with VPA_BUSINESS_QR deleted.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-paytmqr6nu5ur@ptys-REF992817"))
                .isFalse();
    }

    @Test
    void excludesPspBrandTokenStandingAloneAsASegment() {
        // A PSP brand repeated as its own segment before its VPA must never be read as a name.
        // Paired with a second real word so the segment passes the 2-4-word shape test and
        // PSP_BRAND_TOKENS is genuinely the only thing that can reject it.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-PHONEPE MERCHANT-phonepe.payments@icici-REF102938"))
                .isFalse();
    }

    @Test
    void ignoresTheStatementOwnBankNamePrecedingTheTransferMarker() {
        // "HDFC BANK LIMITED" is the statement-owner's own institution, not the counterparty --
        // counted as a business signal it would veto a line whose actual payee is a person.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "HDFC BANK LIMITED UPI-SUNITA RAO-sampleuser2@oksbi-REF773821"))
                .isTrue();
    }

    @Test
    void ignoresMultiWordAndTrailingWordIssuerNames() {
        // The earlier single-word-prefix regex only handled "<WORD> BANK LIMITED", so every
        // multi-word issuer still vetoed itself. Slicing at the transfer marker handles all of
        // them uniformly.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "KOTAK MAHINDRA BANK LIMITED UPI-SUNITA RAO-sampleuser2@oksbi-REF7"))
                .isTrue();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "STATE BANK OF INDIA UPI-SUNITA RAO-sampleuser2@oksbi-REF7"))
                .isTrue();
    }

    @Test
    void excludesRetailTradeNamesCarryingARecognisedEntityWord() {
        // Kept from the retail-vocabulary attempt: these carry an unambiguous entity word, so they
        // are safe to veto. Their common-noun neighbours (SALES, DIGITAL, SNACKS, XEROX, ...) were
        // REVERTED -- see doesNotVetoOnFreeTextRemarkWords below for the measurement that forced it.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-SHREE RAM MEDICOS-shreeram@paytm-REF556677")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-GEETA MEDICAL STORE-geeta@ybl-REF11")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-CHENNAI SILKS RETAIL-silks@hdfcbank-REF889912")).isFalse();
    }

    @Test
    void doesNotVetoOnFreeTextRemarkWords() {
        // Indian UPI narrations end with the PAYER'S FREE-TEXT REMARK. An attempt to catch
        // suffix-less businesses by adding retail vocabulary (AUTO, BOOKS, FRESH, SUPER, CEMENT,
        // STEEL, SOLAR, TOYS, ENERGY, TAILORS, FURNITURE, DIGITAL) was measured against realistic
        // narrations and removed 12 of 18 genuine detections while closing 1 of 28 misfires --
        // because these are common English nouns, not business markers. Reverted; this pins it.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-AMIT VERMA-amit@okicici-REF11-Fresh veggies money")).isTrue();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-GURPREET KAUR-gurpreet@oksbi-REF12-Toys for kid")).isTrue();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-MANOJ TIWARI-manoj@okhdfcbank-REF13-Steel work")).isTrue();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-NEHA AGARWAL-neha@ybl-REF14-Furniture money")).isTrue();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-rajesh@ybl-REF15-Auto")).isTrue();
    }

    @Test
    void vetoesABusinessNamedBeforeTheTransferMarker() {
        // A previous fix DISCARDED all pre-marker text to stop an issuer prefix vetoing a real
        // transfer. That silently disabled the business veto for the entire left-hand side: even
        // PVT/LTD/TRADERS -- the strongest corporate signal there is -- became invisible whenever
        // the counterparty was named before the rail token, filing a vendor payment as a personal
        // transfer at rule-grade confidence. Only BANK/LIMITED/LTD are discounted there now.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "RAMESH TRADERS PVT LTD-UPI-ramesh@ybl-RAMESH KUMAR GUPTA")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "PAYTM SERVICES PVT LTD UPI/DR/123456/RAJESH KUMAR/YESB")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "LITTLE FLOWER SCHOOL/NEFT/HDFC0XXXXXX/SUNITA RANI VERMA")).isFalse();
    }

    @Test
    void findsTheCounterpartyWhenTheRailTokenComesLast() {
        // This repo's own trace fixtures contain narrations whose rail token is the LAST segment.
        // Slicing the narration at the marker left nothing but the rail itself to scan, silently
        // losing the feature for a whole bank format.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "SUNITA RAO/HDFC1234/IMPS")).isTrue();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "PRIYA SHARMA-UPI-REF773821")).isTrue();
    }

    @Test
    void survivesCharactersWhoseUppercaseIsLongerThanTheOriginal() {
        // The marker index was taken on description.toUpperCase() and applied to the ORIGINAL
        // string. Java's toUpperCase can LENGTHEN text -- and PDFBox emits the U+FB01 'fi' ligature
        // verbatim for fonts whose ToUnicode maps it -- so the two desynchronised, mis-slicing the
        // narration and eventually throwing StringIndexOutOfBoundsException. An uncaught runtime
        // exception here fails a whole import row rather than degrading to "Other".
        String ligatures = "ﬁ".repeat(25);
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                ligatures + " UPI-SUNITA RAO-x@ybl-REF1")).isTrue();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "ß".repeat(30) + " UPI-SUNITA RAO-x@ybl-REF1")).isTrue();
    }

    @Test
    void handlesNullAndBlankSafely() {
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(null)).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer("")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer("   ")).isFalse();
    }
}
