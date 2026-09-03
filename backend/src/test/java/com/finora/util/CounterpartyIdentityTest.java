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

    @Test
    void aKeyNeverExceedsTheColumnItIsStoredIn() {
        // Not a theoretical bound. transactions.description is VARCHAR(500) and this pipeline joins
        // wrapped continuation rows into one narration, so a long space-only narration is ordinary
        // input. SEGMENTS only splits on - / _ | : , so such a narration is a SINGLE segment and
        // meaningfulPart concatenates all of it: measured before the cap existed, this input keyed
        // to 505 characters against a VARCHAR(120) column. Uncapped, the INSERT fails -- and in
        // ImportService.confirm one such row fails the user's whole statement.
        String longNarration = ("ALPHA ".repeat(84)).substring(0, 500);
        assertThat(longNarration).hasSize(500);

        String key = CounterpartyIdentity.keyOf(longNarration);

        assertThat(key).hasSizeLessThanOrEqualTo(CounterpartyIdentity.MAX_KEY_LENGTH);
        assertThat(CounterpartyIdentity.MAX_KEY_LENGTH).isEqualTo(120); // == V142's VARCHAR(120)
    }

    @Test
    void aVpaKeyIsCappedToo_theLocalPartHasNoBoundOfItsOwn() {
        // "[A-Za-z0-9._]{2,}" is unbounded, so the VPA branch is no safer than the name branch and
        // must not be left to the assumption that handles are short.
        String key = CounterpartyIdentity.keyOf("UPI-" + "a".repeat(300) + "@ybl-REF40");

        assertThat(key).startsWith("vpa:");
        assertThat(key).hasSizeLessThanOrEqualTo(CounterpartyIdentity.MAX_KEY_LENGTH);
    }

    @Test
    void twoRowsCarryingTheSameOverLongNarrationStillGroupTogether() {
        // Why the cap truncates instead of returning "": grouping is the only thing this key is
        // for, and truncation preserves it. Returning "" would throw away a usable group.
        String narration = ("BETA ".repeat(101)).substring(0, 500);

        assertThat(CounterpartyIdentity.keyOf(narration))
                .isEqualTo(CounterpartyIdentity.keyOf(narration))
                .isNotEmpty();
    }
}
