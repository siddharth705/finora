package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wildcards are the whole point: parameter binding already makes these queries safe from
 * injection, and made nobody safe from {@code %} meaning "anything".
 */
class LikePatternsTest {

    @Test
    void escapesPercent_soALiteralPercentStopsMatchingEverything() {
        // "50%" used to match every row containing "50" followed by anything at all.
        assertThat(LikePatterns.escape("50%")).isEqualTo("50\\%");
    }

    @Test
    void escapesUnderscore_whichIsOrdinaryTextInThisDomain() {
        // Audit actions are literally underscore-separated ("USER_LOGIN"), so an unescaped _
        // silently over-matched every search of the Activity Feed.
        assertThat(LikePatterns.escape("USER_LOGIN")).isEqualTo("USER\\_LOGIN");
    }

    @Test
    void escapesBackslashFirst_orTheEscapesItAddsGetEscapedAgain() {
        // Doing this in the wrong order turns "\%" into "\\\%": a literal backslash followed by a
        // wildcard, which is neither what was typed nor what was meant.
        assertThat(LikePatterns.escape("a\\b")).isEqualTo("a\\\\b");
        assertThat(LikePatterns.escape("100%\\")).isEqualTo("100\\%\\\\");
    }

    @Test
    void leavesOrdinaryTextUntouched() {
        assertThat(LikePatterns.escape("Amazon")).isEqualTo("Amazon");
        assertThat(LikePatterns.escape("O'Brien-Smith")).isEqualTo("O'Brien-Smith");
    }

    @Test
    void passesNullAndEmptyThrough_becauseCallersTreatThoseAsNoFilter() {
        assertThat(LikePatterns.escape(null)).isNull();
        assertThat(LikePatterns.escape("")).isEmpty();
    }
}
