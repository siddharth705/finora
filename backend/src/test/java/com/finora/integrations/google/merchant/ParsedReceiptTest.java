package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The structural half of C5's two-layer validation — see {@link ParsedReceiptValidatorTest} for the
 * business-rule half. A null field here is always a parser bug, never a legitimate "unknown" value,
 * so the constructor refuses it rather than letting it become a receipt that reaches the validator
 * (or worse, staging) missing something it needs. {@code counterpartyName} is the one exception —
 * see the two tests at the bottom.
 */
class ParsedReceiptTest {

    private static final Money AMOUNT = Money.of(new BigDecimal("100.00"));
    private static final LocalDate DATE = LocalDate.of(2026, 8, 10);

    @Test
    void nullGmailMessageIdIsRejected() {
        assertThatThrownBy(() -> new ParsedReceipt(null, "amazon.in", null, AMOUNT, DATE, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gmailMessageId");
    }

    @Test
    void blankMerchantDomainIsRejected() {
        assertThatThrownBy(() -> new ParsedReceipt("msg-1", "  ", null, AMOUNT, DATE, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merchantDomain");
    }

    @Test
    void nullAmountIsRejected() {
        assertThatThrownBy(() -> new ParsedReceipt("msg-1", "amazon.in", null, null, DATE, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void nullTransactionDateIsRejected() {
        assertThatThrownBy(() -> new ParsedReceipt("msg-1", "amazon.in", null, AMOUNT, null, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionDate");
    }

    @Test
    void confidenceOutsideZeroToOneIsRejected() {
        assertThatThrownBy(() -> new ParsedReceipt("msg-1", "amazon.in", null, AMOUNT, DATE, 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void counterpartyNameIsOptional() {
        ParsedReceipt withoutCounterparty =
                new ParsedReceipt("msg-1", "amazon.in", null, AMOUNT, DATE, 0.9);

        assertThat(withoutCounterparty.counterpartyName()).isNull();
    }

    @Test
    void counterpartyNameRoundTripsWhenProvided() {
        ParsedReceipt withCounterparty =
                new ParsedReceipt("msg-1", "phonepe.com", "Sunrise General Store", AMOUNT, DATE, 0.9);

        assertThat(withCounterparty.counterpartyName()).isEqualTo("Sunrise General Store");
    }
}
