package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interesting assertions here are the ORDERING ones. Any single type is easy to get right in
 * isolation; the failure mode that matters is a row carrying two signals being typed by the weaker
 * one, and that is what most of these pin.
 */
class CounterpartyClassifierTest {

    @Test
    void aMerchantAcquiringRailMakesItABusiness_whateverNameIsOnThePayeeLine() {
        // The case the whole layer exists for: a small merchant collecting on what looks exactly
        // like a person. Measured at 543 rows on the real corpus.
        assertThat(CounterpartyClassifier.classify("UPI-RAJESH KUMAR-Q710750321@ybl-REF21"))  // synthetic-ok
                .isEqualTo(CounterpartyType.BUSINESS);
        assertThat(CounterpartyClassifier.classify("UPI-SUNIL VERMA-payu@sample-REF22"))
                .isEqualTo(CounterpartyType.BUSINESS);
    }

    @Test
    void aCorporateSuffixMakesItABusinessWithoutAnyRailMarker() {
        assertThat(CounterpartyClassifier.classify("NEFT ACME TECHNOLOGIES PVT LTD REF23"))
                .isEqualTo(CounterpartyType.BUSINESS);
    }

    @Test
    void anIndividualWithNoBusinessSignalIsAPerson() {
        assertThat(CounterpartyClassifier.classify("UPI-SUNIL VERMA-sampleuser@ybl-REF24"))
                .isEqualTo(CounterpartyType.PERSON);
    }

    @Test
    void bankGeneratedActivityIsTheInstitution_notAGenericBusiness() {
        // Ordering guard. "BANK" is in the detector's business-token vocabulary -- correct for
        // vetoing a person, wrong as a final type. If FINANCIAL_MECHANISM/FINANCIAL_ENTITY stopped
        // running first, every interest credit and charge on the corpus would type as BUSINESS.
        assertThat(CounterpartyClassifier.classify("SB INT CREDIT"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
        assertThat(CounterpartyClassifier.classify("ATM WDL CHARGES"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
        assertThat(CounterpartyClassifier.classify("NEFT SAMPLE BANK LIMITED REF25"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
    }

    @Test
    void aFinancialInstitutionOutranksACorporateSuffixOnTheSameRow() {
        // "SAMPLE SECURITIES PVT LTD" carries both an institution word and a corporate suffix.
        // A broker is not a merchant, and answering BUSINESS here would lose that distinction for
        // anything that later reasons about investment flows.
        assertThat(CounterpartyClassifier.classify("NEFT SAMPLE SECURITIES PVT LTD REF26"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
    }

    @Test
    void aTaxBodyIsGovernment_notABusiness() {
        assertThat(CounterpartyClassifier.classify("GST PAYMENT CHALLAN REF27"))
                .isEqualTo(CounterpartyType.GOVERNMENT);
    }

    @Test
    void nothingIdentifiableIsUnknownRatherThanAGuess() {
        // ~530 corpus rows land here. UNKNOWN is the honest answer, and the codebase prefers it to
        // a confident wrong one -- this test exists so a future "improvement" that assigns a
        // default type has to delete an assertion that says not to.
        assertThat(CounterpartyClassifier.classify("UPI/REF28/UPI")).isEqualTo(CounterpartyType.UNKNOWN);
        assertThat(CounterpartyClassifier.classify("")).isEqualTo(CounterpartyType.UNKNOWN);
        assertThat(CounterpartyClassifier.classify(null)).isEqualTo(CounterpartyType.UNKNOWN);
    }

    @Test
    void theClassifierReusesTheDetectorsOwnMarkerSet_soTheTwoCannotDrift() {
        // Not a behaviour test -- a coupling test. The marker set has already grown twice; a second
        // copy inside this class would have missed the second wave and typed 232 corpus rows
        // UNKNOWN while the detector correctly treated them as businesses.
        String secondWaveMarker = "UPI-ANITA DESAI-PAYTMQR2810050501@paytm-REF29";  // synthetic-ok
        assertThat(PersonToPersonTransferDetector.hasMerchantAcquirerMarker(secondWaveMarker)).isTrue();
        assertThat(CounterpartyClassifier.classify(secondWaveMarker)).isEqualTo(CounterpartyType.BUSINESS);
    }

    @Test
    void cashbackAndRewardCreditsAreTheInstitution_notAMerchantAndNotAPerson() {
        // 18 of the 40 inbound rows in the rail-less residue were these -- the largest single group
        // there by count, though near-zero by value, which is why a value-weighted view never
        // surfaced them. The counterparty is the card issuer running the programme.
        assertThat(CounterpartyClassifier.classify("CASHBACK EARNED JUL"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
        assertThat(CounterpartyClassifier.classify("REWARD POINTS CREDIT"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
    }
}
