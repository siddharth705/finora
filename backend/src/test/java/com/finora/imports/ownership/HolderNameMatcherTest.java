package com.finora.imports.ownership;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/proposals/account-ownership-intelligence-proposal.md §3.1 point 1 and point 3: a token-set
 * comparison, not edit distance -- names vary by initials, word order, and honorifics far more than
 * they vary by typo, and a joint statement's extracted holder name must not be split in a way that
 * separates a shared surname from the first name it belongs to.
 */
class HolderNameMatcherTest {

    @Test
    void exactNameMatches() {
        assertThat(HolderNameMatcher.isLikelyMatch("Rahul Sharma", "Rahul Sharma")).isTrue();
    }

    @Test
    void caseAndWhitespaceDoNotAffectMatching() {
        assertThat(HolderNameMatcher.isLikelyMatch("  rahul   SHARMA  ", "RAHUL sharma")).isTrue();
    }

    @Test
    void anInitialOnTheStatementMatchesTheFullProfileName() {
        assertThat(HolderNameMatcher.isLikelyMatch("R Sharma", "Rahul Sharma")).isTrue();
    }

    @Test
    void anHonorificAndMiddleInitialStillMatch() {
        assertThat(HolderNameMatcher.isLikelyMatch("MR R K SHARMA", "Rahul Sharma")).isTrue();
    }

    @Test
    void tokenOrderDoesNotMatter() {
        assertThat(HolderNameMatcher.isLikelyMatch("SHARMA RAHUL K", "Rahul Sharma")).isTrue();
    }

    @Test
    void aDifferentPersonDoesNotMatch() {
        assertThat(HolderNameMatcher.isLikelyMatch("Sunil Verma", "Rahul Sharma")).isFalse();
    }

    @Test
    void aSpousesSeparateNonJointAccountDoesNotMatch() {
        // Different real holder, not a joint account -- deliberately still flagged, per the design
        // doc: a genuinely different name should warn, and continuing is always available.
        assertThat(HolderNameMatcher.isLikelyMatch("Priya Sharma", "Rahul Sharma")).isFalse();
    }

    @Test
    void jointAccountJoinedByAndMatchesEitherHolder() {
        assertThat(HolderNameMatcher.isLikelyMatch("RAHUL AND PRIYA SHARMA", "Rahul Sharma")).isTrue();
        assertThat(HolderNameMatcher.isLikelyMatch("RAHUL AND PRIYA SHARMA", "Priya Sharma")).isTrue();
    }

    @Test
    void jointAccountJoinedByAmpersandMatches() {
        assertThat(HolderNameMatcher.isLikelyMatch("RAHUL SHARMA & PRIYA SHARMA", "Rahul Sharma")).isTrue();
    }

    @Test
    void jointAccountJoinedByOrMatches() {
        assertThat(HolderNameMatcher.isLikelyMatch("RAHUL SHARMA OR PRIYA SHARMA", "Priya Sharma")).isTrue();
    }

    @Test
    void jointAccountStillRejectsATrulyUnrelatedProfileName() {
        assertThat(HolderNameMatcher.isLikelyMatch("RAHUL AND PRIYA SHARMA", "Sunil Verma")).isFalse();
    }

    @Test
    void blankExtractedNameNeverMatches() {
        assertThat(HolderNameMatcher.isLikelyMatch("", "Rahul Sharma")).isFalse();
        assertThat(HolderNameMatcher.isLikelyMatch(null, "Rahul Sharma")).isFalse();
    }

    @Test
    void blankProfileNameNeverMatches() {
        assertThat(HolderNameMatcher.isLikelyMatch("Rahul Sharma", "")).isFalse();
        assertThat(HolderNameMatcher.isLikelyMatch("Rahul Sharma", null)).isFalse();
    }
}
