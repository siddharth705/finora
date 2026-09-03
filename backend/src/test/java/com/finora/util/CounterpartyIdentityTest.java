package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CounterpartyIdentityTest {

    @Test
    void theVpaLocalPartIsTheKey_andTheHandleIsDroppedOnPurpose() {
        // One person collecting on two PSPs is ONE counterparty. Keeping the handle would split
        // them, which is the single most common way a naive key inflates the counterparty count and
        // makes the review queue look longer than it is.
        String onYbl = CounterpartyIdentity.keyOf("UPI-SUNIL VERMA-sampleuser@ybl-REF31");
        String onPaytm = CounterpartyIdentity.keyOf("UPI/SUNIL VERMA/sampleuser@paytm/REF32");
        assertThat(onYbl).isEqualTo(onPaytm);
        assertThat(onYbl).isEqualTo("vpa:sampleuser");
        assertThat(CounterpartyIdentity.isStrong(onYbl)).isTrue();
    }

    @Test
    void theVpaKeySurvivesTheNameBeingTruncatedDifferently() {
        // This is why the VPA beats the name. Banks truncate the payee differently per statement
        // layout; MerchantNormalizationEngine's first-significant-token grouping is at the mercy of
        // that, and the same payee becomes two merchants.
        assertThat(CounterpartyIdentity.keyOf("UPI-SUNIL VERMA-sampleuser@ybl-REF33"))
                .isEqualTo(CounterpartyIdentity.keyOf("UPI-SUNIL VER-sampleuser@ybl-REF34"));
    }

    @Test
    void aNarrationWithNoVpaFallsBackToAWeakNameKey() {
        String key = CounterpartyIdentity.keyOf("NEFT/ACME TECHNOLOGIES/REF35");
        assertThat(key).startsWith("name:");
        // Weak on purpose: a caller must be able to tell a derived guess from an identity, because
        // the two justify very different UI (auto-group vs ask the user to confirm).
        assertThat(CounterpartyIdentity.isStrong(key)).isFalse();
    }

    @Test
    void referenceHeavySegmentsAreSkippedRatherThanKeyedOn() {
        // Keying on an RRN would make every transaction its own counterparty -- the exact opposite
        // of the point. The reference segment must lose to the payee segment.
        String key = CounterpartyIdentity.keyOf("NEFT/ACME TECHNOLOGIES/CITIN12345678/REF36");
        assertThat(key).isEqualTo("name:acme technologies");
    }

    @Test
    void railWordsAloneAreNotAnIdentity() {
        assertThat(CounterpartyIdentity.keyOf("UPI/REF37/UPI")).isEmpty();
        assertThat(CounterpartyIdentity.keyOf("")).isEmpty();
        assertThat(CounterpartyIdentity.keyOf(null)).isEmpty();
        assertThat(CounterpartyIdentity.isStrong("")).isFalse();
        assertThat(CounterpartyIdentity.isStrong(null)).isFalse();
    }

    @Test
    void theKeyIsStableAcrossCasingAndSurroundingNoise() {
        assertThat(CounterpartyIdentity.keyOf("UPI-x-SampleUser@YBL-REF38"))
                .isEqualTo(CounterpartyIdentity.keyOf("upi-y-sampleuser@ybl-REF39"));
    }
}
