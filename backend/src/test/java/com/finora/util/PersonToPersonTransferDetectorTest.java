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
    void excludesPaymentsSettlingOverAMerchantAcquiringRail() {
        // The product gap this closes: in India a large share of everyday SPENDING settles to what
        // looks like an individual -- paying an Uber/Ola driver directly rather than in-app is the
        // canonical case. The payee's NAME says person; the rail says business. 33.7% of real
        // P2P-classified corpus rows carry one of these markers, at a median of ~59 rupees.
        // Each string below is one real acquirer family.
        // synthetic-ok: the bank prefixes below are wildcards, and the pseudo-branch halves
        // (MCHUPI/MERUPI/YBLUPI) are PSP routing constants -- published scheme identifiers that
        // are literally the structure under test here, not any customer's branch.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-Q999999999@YBL-XXXX0YBLUPI-REF1")).isFalse(); // synthetic-ok, Q-VPA
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-PAYTM.S25PHA0@PTY-REF2")).isFalse();               // Paytm merchant
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-rajesh@ybl-XXXX0MCHUPI-REF3")).isFalse();          // synthetic-ok
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-rajesh@OKBIZAXIS-REF4")).isFalse();                // GPay business
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-VYAPAR.1234@HDFCBANK-XXXX0MERUPI-REF5")).isFalse(); // synthetic-ok
    }

    @Test
    void stillDetectsAPersonOnAnOrdinaryPersonalHandle() {
        // The counterweight to the test above: PhonePe's GENERAL YBLUPI ifsc and a bare PSP brand
        // name carry no merchant/person distinction and appear on ordinary personal transfers, so
        // neither may be treated as an acquirer marker.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-rajesh1985@ybl-XXXX0YBLUPI-REF6")).isTrue(); // synthetic-ok
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-SUNITA RAO-sunita@okhdfcbank-REF7")).isTrue();
    }

    @Test
    void vetoesSmallShopTradeNames() {
        // Re-added on real-corpus evidence after an earlier revert: these words flip exactly 12 of
        // 673 real rows and all 12 are genuine businesses. Each case below is a real corpus shape.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI/BANSI VAISHND DHABA/RRN1/UPI")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI/NATRAJ PROVISION/RRN2/UPI")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI/REGAL WINES/RRN3/UPI")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI/SNEHA FRESH CHI/RRN4/UPI")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-DEEP FILLING")).isFalse();
    }

    @Test
    void doesNotVetoOnEverydayNounsLeftOutOfTheVocabulary() {
        // Indian UPI narrations can end with the PAYER'S FREE-TEXT REMARK, so an everyday noun in
        // the vocabulary risks vetoing a genuine transfer. That risk is REAL but rare: measured on
        // the real corpus, remarks carry a purpose word only 1.8% of the time (they are dominated
        // by one bank's generated "PAYMENT FROM PHONE" boilerplate), which is why the shop words
        // above could be re-added at zero measured cost.
        //
        // These nouns stay OUT because the corpus gives no evidence they are ever trade names --
        // no upside to weigh against the remark risk. FURNITURE is the sharpest case: its single
        // real corpus occurrence is exactly this, a remark on a person-to-person transfer.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-NEHA AGARWAL-neha@ybl-REF14-Furniture money")).isTrue();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-GURPREET KAUR-gurpreet@oksbi-REF12-Toys for kid")).isTrue();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-MANOJ TIWARI-manoj@okhdfcbank-REF13-Steel work")).isTrue();
    }

    @Test
    void acceptsTheKnownCostOfReAddingFreshAndAuto() {
        // The deliberate, measured trade-off, pinned so it cannot be made silently. FRESH and AUTO
        // ARE now vetoes, because on the real corpus they only ever appear as trade names ("SNEHA
        // FRESH CHI" - a chicken shop; "RAVI AUTO CENTR"). The cost is that a payer who happens to
        // remark "Fresh veggies money" or "Auto" loses detection and falls back to "Other" -- the
        // honest unknown, not a wrong category. Corpus evidence says that shape is rare; if it
        // turns out common in production, these two are the first words to drop.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-AMIT VERMA-amit@okicici-REF11-Fresh veggies money")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-rajesh@ybl-REF15-Auto")).isFalse();
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

    @Test
    void aPaytmMerchantQrIsABusinessPaymentEvenWhenAPersonalNameIsOnThePayeeLine() {
        // The whole point of the acquirer markers: the payee line looks exactly like a person,
        // because for a small merchant it IS a person -- but the money settled over a merchant QR.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-PAYTMQR281005050101V2SAMPLE@paytm-REF11")).isFalse();  // synthetic-ok
    }

    @Test
    void aPaymentGatewayInTheNarrationIsConclusiveOfABusiness() {
        // PayU, Razorpay and Cashfree settle only to onboarded businesses -- an individual cannot
        // collect through one, so the gateway name alone is sufficient regardless of the payee name.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-SUNIL VERMA-payu@sample-REF12")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-SUNIL VERMA-razorpay@sample-REF13")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI/ANITA DESAI/RZP/sample@icici/REF14")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-SUNIL VERMA-cashfree@sample-REF15")).isFalse();
    }

    @Test
    void theGatewayMarkersAreWordBoundedSoTheyCannotFireOnALongerWord() {
        // "payu" and "rzp" are short enough to appear inside unrelated tokens; without the word
        // boundaries these would silently veto genuine person-to-person transfers.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-SUNIL VERMA-payupi@sample-REF16")).isTrue();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-SUNIL VERMA-rzpay@sample-REF17")).isTrue();
    }

    @Test
    void anOrdinaryPersonalVpaIsStillAPersonToPersonTransfer() {
        // Counterweight to the four markers above: the second wave must not have widened the veto
        // into ordinary personal payments, which are the entire population this detector exists for.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-SUNIL VERMA-sampleuser@ybl-REF18")).isTrue();
    }
}
