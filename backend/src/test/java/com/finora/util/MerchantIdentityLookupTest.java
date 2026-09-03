package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantIdentityLookupTest {

    @Test
    void aNamedMerchantIsRecognised() {
        assertThat(MerchantIdentityLookup.namesKnownMerchant("UPI-AMAZON SELLER-REF41")).isTrue();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("SWIGGY ORDER REF42")).isTrue();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("NETFLIX SUBSCRIPTION")).isTrue();
    }

    @Test
    void aMechanismOrPurposeWordIsNotAMerchant() {
        // The bug this guard exists to prevent: a salary credit has no merchant on the other side,
        // and typing one as a business would be a new error introduced by the fix, not a fix.
        assertThat(MerchantIdentityLookup.namesKnownMerchant("SALARY CREDIT JUL")).isFalse();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("ATM WITHDRAWAL")).isFalse();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("EMI PAYMENT DEDUCTION")).isFalse();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("MUTUAL FUND PURCHASE")).isFalse();
    }

    @Test
    void aGenericTradeNounIsNotAnIdentityEitherEvenThoughItImpliesABusiness() {
        // "restaurant" does mean a business, but it is not an entity. It is already covered by
        // PersonToPersonTransferDetector.hasBusinessToken; letting it in here would quietly turn
        // this class into a second business-token vocabulary, which is the duplication the seam
        // exists to avoid.
        assertThat(MerchantIdentityLookup.namesKnownMerchant("SOME RESTAURANT BILL")).isFalse();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("LOCAL PHARMACY")).isFalse();
    }

    @Test
    void everyExcludedTermStillExistsUpstream_soARenameCannotLeaveAStaleExclusion() {
        // The entity set is "everything in CategoryRules minus these". If a keyword upstream is
        // renamed or dropped, the matching exclusion here becomes dead -- and worse, silently
        // widens what counts as a merchant identity. This is the only thing that catches that.
        assertThat(CategoryRules.allKeywords())
                .as("stale exclusions in MerchantIdentityLookup.NON_ENTITY_TERMS")
                .containsAll(MerchantIdentityLookup.NON_ENTITY_TERMS);
    }

    @Test
    void theEntitySetIsDerivedFromTheOneVocabulary_notCopiedFromIt() {
        // Coupling test, same intent as the marker-set one on CounterpartyClassifierTest: a second
        // copy of the merchant names would drift the moment either side gained a brand.
        assertThat(MerchantIdentityLookup.knownEntityTerms())
                .isSubsetOf(CategoryRules.allKeywords())
                .contains("amazon", "swiggy", "zerodha")
                .doesNotContain("salary", "atm withdrawal");
    }

    @Test
    void matchingIsWordBounded() {
        // CategoryRules documents "ola" matching inside "cola" as a real false positive. The same
        // discipline has to hold here or this becomes a new source of it.
        assertThat(MerchantIdentityLookup.namesKnownMerchant("COLA AND SNACKS")).isFalse();
    }

    @Test
    void nullAndBlankAreSafe() {
        assertThat(MerchantIdentityLookup.namesKnownMerchant(null)).isFalse();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("")).isFalse();
    }
}
