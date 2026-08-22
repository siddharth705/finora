package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last check before a receipt would reach a user — Phase C5. Every test here is answering the
 * same question from a different angle: can a syntactically valid but nonsensical
 * {@link ParsedReceipt} slip past this and become something a person has to notice is wrong
 * themselves?
 */
class ParsedReceiptValidatorTest {

    // Fixed instant so "is this date in the future" is deterministic rather than depending on the
    // day the suite happens to run.
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC);

    private final ParsedReceiptValidator validator = new ParsedReceiptValidator(FIXED_CLOCK);

    @Test
    void anOrdinaryReceiptHasNoViolations() {
        ParsedReceipt receipt = receipt(Money.of(new BigDecimal("1299.00")), LocalDate.of(2026, 8, 10));

        assertThat(validator.validate(receipt)).isEmpty();
        assertThat(validator.isValid(receipt)).isTrue();
    }

    @Test
    @DisplayName("a zero amount is flagged -- extraction can be honest while staging still declines it")
    void aZeroAmountIsFlagged() {
        ParsedReceipt receipt = receipt(Money.ZERO, LocalDate.of(2026, 8, 10));

        assertThat(validator.validate(receipt))
                .extracting(ParsedReceiptValidator.Violation::field)
                .containsExactly("amount");
    }

    @Test
    void aNegativeAmountIsFlagged() {
        ParsedReceipt receipt = receipt(Money.of(new BigDecimal("-500.00")), LocalDate.of(2026, 8, 10));

        assertThat(validator.validate(receipt))
                .extracting(ParsedReceiptValidator.Violation::field)
                .containsExactly("amount");
    }

    /**
     * The case this validator exists for: a parser that read digits from the wrong place entirely
     * (an order number, a phone number) produces a syntactically fine {@link Money} that is nowhere
     * near a plausible personal transaction.
     */
    @Test
    @DisplayName("an implausibly large amount is flagged, not silently staged")
    void anImplausiblyLargeAmountIsFlagged() {
        ParsedReceipt receipt = receipt(
                ParsedReceiptValidator.MAX_PLAUSIBLE_AMOUNT.add(Money.of(new BigDecimal("0.01"))),
                LocalDate.of(2026, 8, 10));

        assertThat(validator.validate(receipt))
                .extracting(ParsedReceiptValidator.Violation::field)
                .containsExactly("amount");
    }

    @Test
    void theMaximumPlausibleAmountItselfIsNotFlagged() {
        ParsedReceipt receipt = receipt(ParsedReceiptValidator.MAX_PLAUSIBLE_AMOUNT, LocalDate.of(2026, 8, 10));

        assertThat(validator.validate(receipt)).isEmpty();
    }

    /** A receipt dated meaningfully in the future means the parser read the wrong field -- a
     *  delivery estimate, a subscription renewal date -- not a real transaction date. */
    @Test
    @DisplayName("a transaction date meaningfully in the future is flagged")
    void aFarFutureDateIsFlagged() {
        ParsedReceipt receipt = receipt(Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 9, 1));

        assertThat(validator.validate(receipt))
                .extracting(ParsedReceiptValidator.Violation::field)
                .containsExactly("transactionDate");
    }

    /**
     * The tolerance exists specifically so this does NOT get flagged. A receipt genuinely dated
     * "today" in IST can read as tomorrow relative to a UTC clock at certain hours -- rejecting
     * that would reject ordinary correct receipts near local midnight, not just parser bugs.
     */
    @Test
    @DisplayName("a date within the timezone-skew tolerance is not flagged")
    void aDateOneDayAheadIsWithinTolerance() {
        ParsedReceipt receipt = receipt(Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 16));

        assertThat(validator.validate(receipt)).isEmpty();
    }

    @Test
    void bothAmountAndDateCanBeFlaggedTogether() {
        ParsedReceipt receipt = receipt(Money.ZERO, LocalDate.of(2026, 9, 1));

        assertThat(validator.validate(receipt))
                .extracting(ParsedReceiptValidator.Violation::field)
                .containsExactlyInAnyOrder("amount", "transactionDate");
    }

    private static ParsedReceipt receipt(Money amount, LocalDate date) {
        return new ParsedReceipt("msg-1", "amazon.in", null, amount, date, 0.9);
    }
}
