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
        // Deliberately carries NO business-suffix token: an earlier version of this test used
        // "...ENTERPRISES...", which containsBusinessSignal rejected on its own, so the test
        // passed even with the transfer-marker gate deleted entirely. This wording isolates the
        // gate as the only thing that can reject it.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "RAJESH KUMAR INVOICE 4471"))
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
    void excludesRetailTradeNamesThatCarryNoCorporateSuffix() {
        // Real-shaped Indian merchant narrations that an adversarial review showed misfiring as
        // "a person" before the retail vocabulary was added -- none carry PVT/LTD/ENTERPRISES.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-VIJAY SALES-vijaysales@hdfcbank-REF889912")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RELIANCE DIGITAL-reliance@hdfcbank-REF889912")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-HALDIRAM SNACKS-haldiram@ybl-REF334455")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-SHREE RAM MEDICOS-shreeram@paytm-REF556677")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI/BALAJI XEROX/balaji@okaxis/REF11")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "NEFT-AXIS0XXXXXX-ACME DIGITAL PRINTS-INV882")).isFalse();
    }

    @Test
    void handlesNullAndBlankSafely() {
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(null)).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer("")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer("   ")).isFalse();
    }
}
