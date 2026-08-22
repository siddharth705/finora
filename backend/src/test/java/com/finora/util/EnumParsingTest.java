package com.finora.util;

import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnumParsingTest {

    @Test
    void parse_acceptsTheExactEnumConstantName() {
        assertThat(EnumParsing.parse(Transaction.Type.class, "EXPENSE", "type"))
                .isEqualTo(Transaction.Type.EXPENSE);
    }

    /**
     * Bug 51. Enum.valueOf ran on the raw value with no normalization, so a value that was
     * semantically a match -- just differently cased or padded with whitespace -- was rejected as
     * "Unrecognized" instead of accepted, unless the specific caller happened to normalize it
     * itself first (AdminLearningQueueService did; TransactionService and ImportService didn't).
     */
    @Test
    void parse_isCaseInsensitiveAndTrimsWhitespace() {
        assertThat(EnumParsing.parse(Transaction.Type.class, "expense", "type"))
                .isEqualTo(Transaction.Type.EXPENSE);
        assertThat(EnumParsing.parse(Transaction.Type.class, " EXPENSE", "type"))
                .isEqualTo(Transaction.Type.EXPENSE);
        assertThat(EnumParsing.parse(Transaction.Type.class, "Expense ", "type"))
                .isEqualTo(Transaction.Type.EXPENSE);
    }

    @Test
    void parse_rejectsBlankWithARequiredMessage() {
        assertThatThrownBy(() -> EnumParsing.parse(Transaction.Type.class, "  ", "type"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("type is required");
    }

    @Test
    void parse_rejectsAnUnrecognizedValueWithA400() {
        assertThatThrownBy(() -> EnumParsing.parse(Transaction.Type.class, "not-a-real-type", "type"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unrecognized type");
    }

    @Test
    void parseIfPresent_returnsNull_whenRawIsNull() {
        assertThat(EnumParsing.parseIfPresent(Transaction.Type.class, null, "type")).isNull();
    }

    @Test
    void parseIfPresent_normalizesJustLikeParse() {
        assertThat(EnumParsing.parseIfPresent(Transaction.Type.class, " income", "type"))
                .isEqualTo(Transaction.Type.INCOME);
    }
}
