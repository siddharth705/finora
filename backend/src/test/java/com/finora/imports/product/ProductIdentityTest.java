package com.finora.imports.product;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductIdentityTest {

    @Test
    void theSameProductReimportedNextMonthIsRecognisedNotDuplicated() {
        // The whole reason identity exists. Classification finds the same FD in every monthly
        // statement; without this it creates another one each time and double-counts net worth.
        var june = ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234");
        var july = ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234");

        assertThat(june.matches(july)).isEqualTo(ProductIdentity.Match.EXACT);
    }

    @Test
    void oneNumberSpelledThreeWaysIsStillOneProduct() {
        // Statements render the same number differently across pages of a single document.
        var spaced = ProductIdentity.of("HDFC", FinancialProductType.SAVINGS, "6000 0000 006", "0112");
        var hyphenated = ProductIdentity.of("HDFC", FinancialProductType.SAVINGS, "6000-0000-006", "0112");
        var plain = ProductIdentity.of("HDFC", FinancialProductType.SAVINGS, "60000000006", "0112");

        assertThat(spaced.matches(hyphenated)).isEqualTo(ProductIdentity.Match.EXACT);
        assertThat(spaced.matches(plain)).isEqualTo(ProductIdentity.Match.EXACT);
    }

    @Test
    void aMaskedNumberAloneIsProbableAndGoesToTheUser() {
        // "Probably the same FD" means ask. Silently merging two different deposits corrupts both;
        // silently splitting one duplicates it. Neither is the parser's call to make.
        var known = ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521");
        var alsoKnown = ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521");

        assertThat(known.matches(alsoKnown)).isEqualTo(ProductIdentity.Match.PROBABLE);
        assertThat(known.isResolvable()).as("not strong enough to act on unasked").isFalse();
    }

    @Test
    void sameMaskedDigitsAtTheSameBankButADifferentProductIsNotAMatch() {
        // A savings account and a fixed deposit both ending 4521 is a coincidence, not one product.
        var savings = ProductIdentity.of("HDFC", FinancialProductType.SAVINGS, null, "4521");
        var deposit = ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521");

        assertThat(savings.matches(deposit)).isEqualTo(ProductIdentity.Match.NONE);
    }

    @Test
    void theSameNumberAtDifferentBanksIsNotTheSameProduct() {
        var hdfc = ProductIdentity.of("HDFC", FinancialProductType.SAVINGS, "60000000006", "0112");
        var axis = ProductIdentity.of("AXIS", FinancialProductType.SAVINGS, "60000000006", "0112");

        assertThat(hdfc.matches(axis)).isEqualTo(ProductIdentity.Match.NONE);
    }

    @Test
    void anUnrecognisedInstitutionNeverMatchesAnotherUnrecognisedOne() {
        // BankRegistry's "OTHER" sentinel is not an institution. Treating it as one would make
        // every product from an unrecognised bank the same product -- the worst possible failure
        // here, since it merges unrelated accounts.
        var one = ProductIdentity.of("OTHER", FinancialProductType.SAVINGS, "70000000000007", "3333");
        var two = ProductIdentity.of("OTHER", FinancialProductType.SAVINGS, "70000000000007", "3333");

        assertThat(one.matches(two)).isEqualTo(ProductIdentity.Match.NONE);
        assertThat(one.isResolvable()).isFalse();
    }

    @Test
    void theFullNumberIsNeverRetained() {
        // The key exists for equality checks, not for reading back. A full account number in a
        // column nothing ever displays is customer data stored somewhere nobody would look for it.
        var identity = ProductIdentity.of("HDFC", FinancialProductType.SAVINGS, "40000000000004", "1234");

        assertThat(identity.strongKey()).doesNotContain("40000000000004");
        assertThat(identity.strongKey()).hasSize(64);
        assertThat(identity.toString()).doesNotContain("40000000000004");
    }

    @Test
    void tooFewDigitsToIdentifyAnythingYieldsNoStrongKey() {
        var identity = ProductIdentity.of("HDFC", FinancialProductType.SAVINGS, "12", "12");

        assertThat(identity.strongKey()).isNull();
        assertThat(identity.isResolvable()).isFalse();
    }

    @Test
    void aStoredIdentityCompareseEqualToAFreshlyDiscoveredOne() {
        // Round-trip: what gets written to accounts.product_identity_hash must match what the next
        // import computes, or every re-import silently creates a duplicate.
        var discovered = ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234");
        var fromDatabase = ProductIdentity.stored("HDFC", FinancialProductType.FIXED_DEPOSIT,
                discovered.strongKey(), "1234");

        assertThat(fromDatabase.matches(discovered)).isEqualTo(ProductIdentity.Match.EXACT);
    }

    // IFSC + account holder name fallback (see ProductIdentity's own "When there is no number at
    // all" doc section) -- the real PNB incident: account-number extraction found nothing at all
    // (no full number, no masked digits), so "some evidence" (same bank, same IFSC, same holder)
    // and "no evidence" both used to collapse into the same NONE/NEW outcome. Genericized values
    // per the Synthetic Fixture Policy.

    @Test
    void noAccountNumberAtAll_butMatchingIfscAndHolderName_isProbable() {
        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0XXXXXX", "JOHN DOE");
        var stored = ProductIdentity.stored("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0XXXXXX", "JOHN DOE");

        assertThat(discovered.matches(stored)).isEqualTo(ProductIdentity.Match.PROBABLE);
    }

    @Test
    void ifscAloneWithNoHolderNameIsNotEnoughToMatch() {
        // A branch's IFSC is shared by every customer at that branch -- nowhere near specific
        // enough on its own to suggest a match, let alone merge into one.
        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0XXXXXX", null);
        var stored = ProductIdentity.stored("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0XXXXXX", null);

        assertThat(discovered.matches(stored)).isEqualTo(ProductIdentity.Match.NONE);
    }

    @Test
    void holderNameAloneWithNoIfscIsNotEnoughToMatch() {
        // A holder name alone is shared by every account someone holds at that bank -- exactly the
        // ambiguity this fallback exists to avoid resolving by guessing.
        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals(null, "JOHN DOE");
        var stored = ProductIdentity.stored("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals(null, "JOHN DOE");

        assertThat(discovered.matches(stored)).isEqualTo(ProductIdentity.Match.NONE);
    }

    @Test
    void sameBankAloneWithNeitherIfscNorHolderNameIsNotEnoughToMatch() {
        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null);
        var stored = ProductIdentity.stored("PNB", FinancialProductType.SAVINGS, null, null);

        assertThat(discovered.matches(stored)).isEqualTo(ProductIdentity.Match.NONE);
    }

    @Test
    void ifscAndHolderNameMatch_butDifferentProductTypes_isNotAMatch() {
        // Same person, same branch -- a savings account and a fixed deposit are still two
        // different products, the identical guard the masked-number fallback already applies.
        var savings = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0XXXXXX", "JOHN DOE");
        var deposit = ProductIdentity.of("PNB", FinancialProductType.FIXED_DEPOSIT, null, null)
                .withWeakSignals("PUNB0XXXXXX", "JOHN DOE");

        assertThat(savings.matches(deposit)).isEqualTo(ProductIdentity.Match.NONE);
    }

    @Test
    void ifscAndHolderNameFallback_toleratesCasingAndSpacingDifferences() {
        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("punb0xxxxxx", "John  Doe");
        var stored = ProductIdentity.stored("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0XXXXXX", "JOHN DOE");

        assertThat(discovered.matches(stored)).isEqualTo(ProductIdentity.Match.PROBABLE);
    }

    @Test
    void aStrongKeyOnOneSideStillWinsOverTheWeakSignalFallback() {
        // Not the incident scenario (both sides would lack a number after a failed extraction),
        // but the fallback's own guard: it only applies when THIS side has neither a strong key
        // nor masked digits. A side that has a real number never needs to fall back to IFSC/holder
        // at all -- the ordinary EXACT/NONE-on-disagreement rules above already decide it.
        var withNumber = ProductIdentity.of("PNB", FinancialProductType.SAVINGS,
                "98765432101234", "1234") // synthetic-ok
                .withWeakSignals("PUNB0XXXXXX", "JOHN DOE");
        var withoutNumber = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0XXXXXX", "JOHN DOE");

        assertThat(withNumber.matches(withoutNumber)).isEqualTo(ProductIdentity.Match.NONE);
    }
}
