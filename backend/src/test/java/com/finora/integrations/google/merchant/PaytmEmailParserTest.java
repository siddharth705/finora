package com.finora.integrations.google.merchant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C5 follow-up, 2026-08-22. Unlike {@link PhonePeEmailParserTest}/{@link CredEmailParserTest},
 * there is no real-shaped fixture here — no per-transaction Paytm receipt email was found across
 * 30 real threads reviewed (see docs/proposals/gmail-merchant-template-admin-ui-proposal.md's
 * 2026-08-22 update). This class exists only because the project owner explicitly chose to keep a
 * scaffold in case such mail surfaces later; every path fails closed, proven here with an
 * arbitrary body rather than a claimed-real one.
 */
class PaytmEmailParserTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final PaytmEmailParser parser = new PaytmEmailParser();

    @BeforeEach
    void enableParser() {
        ReflectionTestUtils.setField(parser, "enabled", true);
    }

    @Test
    void canParseOnlyClaimsPaytmsAuthenticatedDomainWhenEnabled() {
        assertThat(parser.canParse("paytm.com")).isTrue();
        assertThat(parser.canParse("paytm.attacker.example")).isFalse();
        assertThat(parser.canParse("phonepe.com")).isFalse();
    }

    @Test
    @DisplayName("disabled by default -- canParse is false until the config property is set")
    void canParseIsFalseWhenNotExplicitlyEnabled() {
        PaytmEmailParser disabledParser = new PaytmEmailParser();

        assertThat(disabledParser.canParse("paytm.com")).isFalse();
    }

    @Test
    @DisplayName("every message is malformed, not implemented -- no real pattern exists yet")
    void everyMessageIsReportedMalformed() {
        SanitizedGmailMessage anyMessage = sanitizer.sanitize(
                "msg-1", "paytm.com", "<p>Any content at all.</p>");

        ParserResult result = parser.parse(anyMessage);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
        assertThat(result.reason()).contains("no verified extraction pattern");
    }
}
