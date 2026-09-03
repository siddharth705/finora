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

    @Test
    void recurringMandateBoilerplateDoesNotFragmentTheSamePayeeAcrossOccurrences() {
        // Measured on the real 29-statement corpus: one AMC's SIP mandate debit produced THREE
        // different keys across its own occurrences purely because the bank appends this
        // boilerplate inconsistently row to row -- bare, "...DEBIT CMP MANDATE DEBIT", and
        // "...Balance DEBIT CMP MANDATE DEBIT" all keyed differently before MANDATE/DEBIT/BALANCE/
        // CMP joined NOISE. Reproduced synthetically here (not the real corpus narration -- see
        // this repo's own "describe, don't quote, real evidence" practice).
        String bare = "SAMPLE ASSET MANAGEMENT LTD";
        String withDebitMandate = "SAMPLE ASSET MANAGEMENT LTD DEBIT CMP MANDATE DEBIT";
        String withBalanceDebitMandate = "SAMPLE ASSET MANAGEMENT LTD Balance DEBIT CMP MANDATE DEBIT";

        String key = CounterpartyIdentity.keyOf(bare);
        assertThat(CounterpartyIdentity.keyOf(withDebitMandate)).isEqualTo(key);
        assertThat(CounterpartyIdentity.keyOf(withBalanceDebitMandate)).isEqualTo(key);
        assertThat(key).isEqualTo("name:sample asset management ltd");
    }

    @Test
    void aRealLongPayeeNameIsNotTruncated() {
        // The regression guard for the fix that was NOT made. A first-N-words cap was proposed,
        // measured against the real corpus, and rejected: 96% of real name: keys are already under
        // 30 characters, and the long tail is dominated by real long payee names -- truncating them
        // would produce a WORSE, more collision-prone key than leaving them alone, the over-merge
        // failure mode this class's own doc says is worse than the status quo. Synthetic shape
        // (proprietor-plus-firm) rather than the real corpus narration -- see this class's own
        // "describe, don't quote" note above.
        String key = CounterpartyIdentity.keyOf(
                "UPI/SAMPLE ENTERPRISES SURNAME FIRSTNAME MIDDLENAME/Q/UPI/");

        assertThat(key).isEqualTo("name:sample enterprises surname firstname middlename");
    }
}
