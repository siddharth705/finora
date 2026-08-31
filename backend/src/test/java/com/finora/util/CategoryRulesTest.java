package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryRulesTest {

    @Test
    void normalize_lowercasesAndStripsPunctuation() {
        assertThat(CategoryRules.normalize("SWIGGY*ORDR9182 BLR")).isEqualTo("swiggy ordr9182 blr");
    }

    @Test
    void normalize_collapsesRepeatedWhitespace() {
        assertThat(CategoryRules.normalize("AMAZON.IN   PAYMTS")).isEqualTo("amazon in paymts");
    }

    @Test
    void normalize_handlesNullSafely() {
        assertThat(CategoryRules.normalize(null)).isEqualTo("");
    }

    @Test
    void extractMerchant_stripsNumericReferenceCodes() {
        assertThat(CategoryRules.extractMerchant("SWIGGY*ORDR9182 BANGALORE IN")).isEqualTo("swiggy bangalore in");
    }

    @Test
    void extractMerchant_limitsToFourTokens() {
        assertThat(CategoryRules.extractMerchant("ONE TWO THREE FOUR FIVE SIX")).isEqualTo("one two three four");
    }

    @Test
    void extractMerchant_fallsBackToUnknownForEmptyInput() {
        assertThat(CategoryRules.extractMerchant("")).isEqualTo("unknown");
    }

    @Test
    void suggestCategory_matchesDiningKeyword() {
        assertThat(CategoryRules.suggestCategory("SWIGGY*ORDR9182 BLR")).isEqualTo("Dining");
    }

    @Test
    void suggestCategory_matchesTransportKeyword() {
        assertThat(CategoryRules.suggestCategory("UBER TRIP 19OCT")).isEqualTo("Transport");
    }

    @Test
    void suggestCategory_matchesTransferKeyword_forCreditCardPayments() {
        assertThat(CategoryRules.suggestCategory("CC PYMT AUTOPAY VISA")).isEqualTo("Transfer");
    }

    @Test
    void suggestCategory_fallsBackToOtherWhenNoRuleMatches() {
        assertThat(CategoryRules.suggestCategory("SOME RANDOM MERCHANT XYZ")).isEqualTo("Other");
    }

    @Test
    void suggestCategory_firstMatchingRuleWins() {
        // "credit card payment" contains no dining/shopping keywords, so it should hit
        // the Transfer rule specifically rather than falling through to Other.
        assertThat(CategoryRules.suggestCategory("credit card payment received")).isEqualTo("Transfer");
    }

    /**
     * Regression test for a substring-collision bug found during review: the Loan EMI rule
     * originally included a bare "emi" keyword, and contains()-based matching means that 3-letter
     * substring is also inside "premium" (p-r-EMI-um). An insurance premium payment would have
     * matched Loan EMI first — Loan EMI is earlier in RULES' insertion order than Insurance, and
     * suggestCategory returns on the first match — even though "lic premium" is a much more
     * specific and correct match sitting right there in the Insurance rule.
     */
    @Test
    void suggestCategory_insurancePremiumIsNotMisclassifiedAsLoanEmi() {
        assertThat(CategoryRules.suggestCategory("LIC PREMIUM PAYMENT ONLINE")).isEqualTo("Insurance");
    }

    /**
     * Same class of bug, different rule: Gifts & Donations originally included a bare "ngo"
     * keyword, which is also a substring of "mongo"/"flamingo"/"bingo"/"tango" — a MongoDB
     * hosting charge would have been misfiled as a donation.
     */
    @Test
    void suggestCategory_mongoDbChargeIsNotMisclassifiedAsGiftsAndDonations() {
        assertThat(CategoryRules.suggestCategory("MONGODB ATLAS CLOUD HOSTING")).isEqualTo("Other");
    }

    /** The compound phrases that replaced the bare "emi" keyword should still catch the real,
     *  common-case EMI deduction lines they were meant to cover. */
    @Test
    void suggestCategory_stillMatchesRealEmiDeductionLines() {
        assertThat(CategoryRules.suggestCategory("HDFC BANK LOAN EMI DEDUCTION")).isEqualTo("Loan EMI");
        assertThat(CategoryRules.suggestCategory("AUTO LOAN EMI PAYMENT NACH")).isEqualTo("Loan EMI");
    }

    /**
     * Regression test for another substring-collision bug found during a later review pass: the
     * Rent rule originally included a bare "rent" keyword, and contains()-based matching means
     * that 4-letter substring is also inside "current" -- a very common word on Indian bank
     * statements ("UPI-CURRENT A/C", "CURRENT ACCOUNT INT"). Fixed systemically by switching
     * suggestCategory's matching to word-boundary regex for every keyword, not just this one.
     */
    @Test
    void suggestCategory_currentAccountIsNotMisclassifiedAsRent() {
        assertThat(CategoryRules.suggestCategory("UPI-CURRENT ACCOUNT INT CREDIT")).isNotEqualTo("Rent");
    }

    /** The compound phrases that replaced the bare "rent" keyword should still catch the real,
     *  common-case rent payment lines they were meant to cover. */
    @Test
    void suggestCategory_stillMatchesRealRentPaymentLines() {
        assertThat(CategoryRules.suggestCategory("HOUSE RENT PAID TO LANDLORD")).isEqualTo("Rent");
        assertThat(CategoryRules.suggestCategory("MONTHLY RENT NEFT PAYMENT")).isEqualTo("Rent");
    }

    /**
     * Same class of bug, caught proactively while fixing the "rent"/"current" collision: the
     * Transport rule's bare "ola" keyword is also a substring of "cola" -- a Coca-Cola purchase
     * on a grocery or dining statement line would have misfired as a cab ride.
     */
    @Test
    void suggestCategory_cocaColaIsNotMisclassifiedAsTransport() {
        assertThat(CategoryRules.suggestCategory("COCA COLA PURCHASE DMART")).isNotEqualTo("Transport");
    }

    /** The word-boundary fix should still catch real Ola cab trips -- this isn't just about
     *  suppressing the false positive, the true positive has to keep working too. */
    @Test
    void suggestCategory_stillMatchesRealOlaCabTrips() {
        assertThat(CategoryRules.suggestCategory("OLA CAB RIDE TO AIRPORT")).isEqualTo("Transport");
    }

    /** Real narration from this project's own bank-statement corpus -- "NWD" (Non-Home-branch
     *  Withdrawal) was falling through every existing Cash Withdrawal keyword to "Other". */
    @Test
    void suggestCategory_matchesNwdAsCashWithdrawal() {
        assertThat(CategoryRules.suggestCategory("NWD-416021XXXXXX5853-14132291-HUZUR")).isEqualTo("Cash Withdrawal");
    }

    @Test
    void suggestCategory_stillMatchesRealInvestmentSips() {
        assertThat(CategoryRules.suggestCategory("UPI-GROWW INVEST TECH")).isEqualTo("Investments");
    }
}
