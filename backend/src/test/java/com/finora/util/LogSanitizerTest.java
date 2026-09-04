package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    @Test
    void sanitize_null_staysNull() {
        assertThat(LogSanitizer.sanitize(null)).isNull();
    }

    @Test
    void sanitize_ordinaryText_isUnchanged() {
        assertThat(LogSanitizer.sanitize("hdfc-statement-march.pdf")).isEqualTo("hdfc-statement-march.pdf");
    }

    @Test
    void sanitize_nonAsciiText_isUnchanged() {
        // Not the injection vector this exists for -- must not be collateral damage.
        assertThat(LogSanitizer.sanitize("विवरण-मार्च.pdf")).isEqualTo("विवरण-मार्च.pdf");
    }

    /** The actual attack this exists to close: a crafted filename forging what looks like a
     *  second, independent log line to anyone reading the raw log file. */
    @Test
    void sanitize_embeddedNewlines_cannotForgeASecondLogLine() {
        String forged = "statement.pdf\r\n[ERROR] fake log line";

        String sanitized = LogSanitizer.sanitize(forged);

        assertThat(sanitized).doesNotContain("\r").doesNotContain("\n");
        assertThat(sanitized).isEqualTo("statement.pdf??[ERROR] fake log line");
    }

    @Test
    void sanitize_otherControlCharacters_areAlsoNeutralised() {
        // Tab, not just CR/LF -- the whole C0 range is out of place here, not an allowlist of two.
        assertThat(LogSanitizer.sanitize("a\tb c")).isEqualTo("a?b c");
    }

    @Test
    void sanitize_preservesLength_soTheResultDoesNotLookTruncated() {
        String withControlChars = "abc\r\ndef";
        assertThat(LogSanitizer.sanitize(withControlChars)).hasSize(withControlChars.length());
    }
}
