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
        // own -- it could just as easily be part of a merchant's trade name. Fails the gate
        // before any name-shape check even runs.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "RAJESH KUMAR ENTERPRISES INVOICE 4471"))
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
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-PAYTMQR6NU5UR@PTYS-REF992817"))
                .isFalse();
    }

    @Test
    void excludesPspBrandTokenStandingAloneAsASegment() {
        // This corpus's narration grammar sometimes repeats a PSP brand as its own segment right
        // before its VPA -- a lone brand token must never be misread as a first name.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-PHONEPE-PHONEPE.PAYMENTS@ICICI-REF102938"))
                .isFalse();
    }

    @Test
    void stripsOwnBankPrefixBeforeCheckingForABusinessSignal() {
        // "HDFC BANK LIMITED" is the statement-owner's own institution name, not the counterparty
        // -- without stripping it, "BANK"/"LIMITED" would spuriously read as a business signal for
        // a line whose actual payee is a person.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "HDFC BANK LIMITED UPI-SUNITA RAO-sampleuser2@oksbi-REF773821"))
                .isTrue();
    }

    @Test
    void handlesNullAndBlankSafely() {
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(null)).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer("")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer("   ")).isFalse();
    }
}
