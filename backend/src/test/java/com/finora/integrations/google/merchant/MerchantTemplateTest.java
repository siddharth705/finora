package com.finora.integrations.google.merchant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pattern compiler — the part of C5.2's experiment that has to be right regardless of how the
 * Uber-vs-Ola comparison turns out. A template author writes a literal label copied out of a real
 * email; this proves that literal text is matched as literal text, not interpreted as regex.
 */
class MerchantTemplateTest {

    private static MerchantTemplate template(String amountPattern, String datePattern) {
        MerchantTemplate template = new MerchantTemplate();
        template.setMerchantDomain("example.test");
        template.setMerchantName("Example");
        template.setReceiptMarker("Receipt");
        template.setAmountPattern(amountPattern);
        template.setDatePattern(datePattern);
        template.setEnabled(true);
        return template;
    }

    @Test
    void matchesReceiptMarkerFindsTheLiteralSubstring() {
        MerchantTemplate t = template("Total: {amount}", "Date: {date}");
        t.setReceiptMarker("Trip Fare");

        assertThat(t.matchesReceiptMarker("Here is your Trip Fare summary")).isTrue();
        assertThat(t.matchesReceiptMarker("A promotional email")).isFalse();
    }

    @Test
    void matchesReceiptMarkerHandlesNullText() {
        assertThat(template("Total: {amount}", "Date: {date}").matchesReceiptMarker(null)).isFalse();
    }

    @Test
    @DisplayName("literal text around the placeholder is matched verbatim, not as regex")
    void literalTextIsRegexEscaped() {
        // Parentheses and a dot are both regex-significant -- a naive compiler would treat "(incl.
        // tax)" as a capture group and an any-character wildcard instead of literal text.
        MerchantTemplate t = template("Total (incl. tax): Rs. {amount}", "On {date}");

        Pattern amount = t.compileAmountPattern();
        Matcher match = amount.matcher("Total (incl. tax): Rs. 199.00");
        assertThat(match.find()).isTrue();
        assertThat(match.group(1)).isEqualTo("199.00");

        // The literal parenthesis must not have opened a real capture group -- confirm there is
        // exactly the one group the amount placeholder is supposed to produce.
        assertThat(amount.matcher("x").groupCount()).isEqualTo(1);
    }

    @Test
    void extractsAnAmountFromTheMiddleOfALine() {
        MerchantTemplate t = template("Fare: {amount} paid", "Date: {date}");

        Matcher match = t.compileAmountPattern().matcher("Ride Fare: 245.00 paid via card");
        assertThat(match.find()).isTrue();
        assertThat(match.group(1)).isEqualTo("245.00");
    }

    @Test
    void extractsADateCapturedByItsOwnPattern() {
        MerchantTemplate t = template("Total: {amount}", "Ride Date: {date}");

        Matcher match = t.compileDatePattern().matcher("Ride Date: August 12, 2026");
        assertThat(match.find()).isTrue();
        assertThat(match.group(1)).isEqualTo("August 12, 2026");
    }

    /**
     * The bug this class shipped with once already: a loose character class over letters, digits,
     * commas and spaces does not stay inside a date, because ordinary sentence text is made of the
     * same characters. A real message has text after the date almost always -- a closing line, the
     * next paragraph -- so this is the ordinary case, not an edge case.
     */
    @Test
    @DisplayName("the date capture stops at the date and does not run into the following sentence")
    void dateCaptureDoesNotConsumeTrailingProse() {
        MerchantTemplate t = template("Total: {amount}", "Trip Date: {date}");

        Matcher match = t.compileDatePattern()
                .matcher("Trip Date: August 12, 2026 We hope you enjoyed your ride.");

        assertThat(match.find()).isTrue();
        assertThat(match.group(1)).isEqualTo("August 12, 2026");
    }

    @Test
    void extractsEachDateShapeReceiptDateFormatsKnows() {
        assertThat(dateGroup("2026-08-12 and more text")).isEqualTo("2026-08-12");
        assertThat(dateGroup("12 August 2026, thank you")).isEqualTo("12 August 2026");
        assertThat(dateGroup("12/08/2026. See you soon")).isEqualTo("12/08/2026");
        assertThat(dateGroup("12-08-2026. See you soon")).isEqualTo("12-08-2026");
    }

    /**
     * dd-MM-yyyy (day first) and yyyy-MM-dd (ISO, year first) are both hyphen-separated, so the
     * capture regex has to tell them apart by digit-group width alone -- a 4-digit first group can
     * only be the ISO shape, a 1-2 digit first group can only be the day-first shape. Proven here
     * because a subtle overlap would silently produce the wrong date rather than fail loudly.
     */
    @Test
    @DisplayName("day-first and ISO hyphenated dates do not get confused with each other")
    void hyphenatedDayFirstAndIsoDatesAreDistinguished() {
        assertThat(dateGroup("2026-08-12")).isEqualTo("2026-08-12");
        assertThat(dateGroup("12-08-2026")).isEqualTo("12-08-2026");

        assertThat(ReceiptDateFormats.tryParse("2026-08-12")).isEqualTo(java.time.LocalDate.of(2026, 8, 12));
        assertThat(ReceiptDateFormats.tryParse("12-08-2026")).isEqualTo(java.time.LocalDate.of(2026, 8, 12));
    }

    private static String dateGroup(String textAfterLabel) {
        MerchantTemplate t = template("Total: {amount}", "Date: {date}");
        Matcher match = t.compileDatePattern().matcher("Date: " + textAfterLabel);
        assertThat(match.find()).as("expected a date match in: " + textAfterLabel).isTrue();
        return match.group(1);
    }

    @Test
    @DisplayName("a template missing the placeholder fails loudly at compile time")
    void missingPlaceholderThrows() {
        MerchantTemplate t = template("Total: Rs.", "Date: {date}");

        assertThatThrownBy(t::compileAmountPattern).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a template with the placeholder twice fails loudly rather than compiling something ambiguous")
    void duplicatePlaceholderThrows() {
        MerchantTemplate t = template("{amount} and again {amount}", "Date: {date}");

        assertThatThrownBy(t::compileAmountPattern).isInstanceOf(IllegalStateException.class);
    }

    /**
     * Same bound, same reasoning, as {@code AmazonEmailParser.TOTAL} and the same test that proves
     * it there — this is not a coincidence, {@link MerchantTemplate}'s amount capture is the
     * identical pattern fragment, and the property has to hold here independently rather than being
     * assumed to carry over.
     */
    @Test
    @DisplayName("an implausibly long amount is rejected outright, not truncated to a wrong value")
    void anOversizedAmountDoesNotMatch() {
        MerchantTemplate t = template("Total: {amount}", "Date: {date}");
        String hugeDigitRun = "1".repeat(25);

        Matcher match = t.compileAmountPattern().matcher("Total: " + hugeDigitRun + ".00");
        assertThat(match.find()).isFalse();
    }
}
