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
}
