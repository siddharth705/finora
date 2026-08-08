package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneMaskingTest {

    @Test
    void mask_aTypicalIndianNumber_keepsTheLeadingPlusAndLastThreeDigitsVisible() {
        assertThat(PhoneMasking.mask("+919000000705")).isEqualTo("+•••••••••705");
    }

    /**
     * The exact bug class this exists to catch: a number stored without its country code (the
     * Twilio incident this session started from) renders visibly differently from a correctly
     * formatted one -- no leading + -- even though every digit is otherwise masked the same way.
     */
    @Test
    void mask_aNumberMissingItsCountryCode_isVisuallyDistinctFromAProperlyFormattedOne() {
        String withCountryCode = PhoneMasking.mask("+919000000705");
        String withoutCountryCode = PhoneMasking.mask("9000000705");

        assertThat(withoutCountryCode).isEqualTo("•••••••705");
        assertThat(withCountryCode).isNotEqualTo(withoutCountryCode);
        assertThat(withCountryCode).startsWith("+");
        assertThat(withoutCountryCode).doesNotStartWith("+");
    }

    @Test
    void mask_null_returnsNull() {
        assertThat(PhoneMasking.mask(null)).isNull();
    }

    @Test
    void mask_blank_returnsNull() {
        assertThat(PhoneMasking.mask("   ")).isNull();
    }

    @Test
    void mask_aNumberTooShortToMaskMeaningfully_returnsItUnchanged() {
        assertThat(PhoneMasking.mask("+91")).isEqualTo("+91");
        assertThat(PhoneMasking.mask("12")).isEqualTo("12");
    }

    @Test
    void mask_exactlyTheVisibleSuffixLength_returnsItUnchanged() {
        // 3 digits after the + -- masking would hide nothing (0 masked chars) while still
        // claiming to mask, so this is treated the same as "too short," not partially masked.
        assertThat(PhoneMasking.mask("+705")).isEqualTo("+705");
    }
}
