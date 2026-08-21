package com.finora.security.mfa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Hand-rolled HMAC/
 * dynamic-truncation logic gets no benefit of the doubt just because it round-trips against
 * itself -- this verifies it against RFC 6238 Appendix B's own published SHA1 test vectors, an
 * independent reference this implementation had no part in producing.
 *
 * <p>RFC 6238's own vectors are 8-digit codes (its test mode); this implementation produces 6.
 * The two are not independent formats to reconcile -- {@code code = binary % 10^digits}, and since
 * 10^6 divides 10^8, the 6-digit code is mathematically just the last 6 digits of the 8-digit one
 * for the identical HMAC output. Truncating the RFC's own published values is deriving expected
 * output from an external reference, not from this class's own code.
 */
class TotpGeneratorTest {

    /** The ASCII string RFC 6238 Appendix B fixes as its SHA1 test key -- 20 bytes, exactly
     *  {@link TotpGenerator}'s own generated-secret length, so this is a realistic key, not a
     *  degenerate edge case. */
    private static final String RFC_TEST_SECRET_BASE32 = TotpGenerator.base32Encode(
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII)); // synthetic-ok -- RFC 6238 Appendix B's own published test key

    @ParameterizedTest(name = "T={0} -> step {1} -> {2}")
    @CsvSource({
            "59,          1,        287082",
            "1111111109,  37037036, 081804",
            "1111111111,  37037037, 050471",
            "1234567890,  41152263, 005924", // synthetic-ok -- RFC 6238 Appendix B's own published test vector (a Unix timestamp)
            "2000000000,  66666666, 279037",
    })
    void matchesRfc6238AppendixBVectors_lastSixDigits(long timeSeconds, long expectedStep, String expectedCode) {
        // Ground the step-number arithmetic itself against the RFC's own worked example too --
        // T=59 -> step 1 is RFC 6238's own Table 1 "Value of T (hex)" = 0000000000000001.
        assertThat(timeSeconds / 30).isEqualTo(expectedStep);
        assertThat(TotpGenerator.generateForStep(RFC_TEST_SECRET_BASE32, expectedStep)).isEqualTo(expectedCode);
    }

    @Test
    void base32RoundTripsThroughGenerateSecretAndBackToTheSameBytes() {
        byte[] original = new byte[20];
        new java.security.SecureRandom().nextBytes(original);

        String encoded = TotpGenerator.base32Encode(original);
        assertThat(TotpGenerator.base32Decode(encoded)).isEqualTo(original);
    }

    @Test
    void generateSecretProducesTheRfc4226RecommendedKeyLength() {
        String secret = TotpGenerator.generateSecret();
        assertThat(TotpGenerator.base32Decode(secret)).hasSize(20); // 160 bits
    }

    @Test
    void verifyAcceptsTheCurrentCode() {
        String secret = TotpGenerator.generateSecret();
        long currentStep = Instant.now().getEpochSecond() / 30;
        String code = TotpGenerator.generateForStep(secret, currentStep);

        assertThat(TotpGenerator.verify(secret, code)).isTrue();
    }

    @Test
    void verifyAcceptsOneStepOfClockDriftEitherWay() {
        String secret = TotpGenerator.generateSecret();
        long currentStep = Instant.now().getEpochSecond() / 30;

        assertThat(TotpGenerator.verify(secret, TotpGenerator.generateForStep(secret, currentStep - 1))).isTrue();
        assertThat(TotpGenerator.verify(secret, TotpGenerator.generateForStep(secret, currentStep + 1))).isTrue();
    }

    @Test
    void verifyRejectsACodeTwoStepsOld() {
        String secret = TotpGenerator.generateSecret();
        long currentStep = Instant.now().getEpochSecond() / 30;

        assertThat(TotpGenerator.verify(secret, TotpGenerator.generateForStep(secret, currentStep - 2))).isFalse();
    }

    @Test
    void verifyRejectsAWrongCode() {
        String secret = TotpGenerator.generateSecret();
        assertThat(TotpGenerator.verify(secret, "000000")).isFalse();
    }

    @Test
    void verifyRejectsMalformedInputWithoutThrowing() {
        String secret = TotpGenerator.generateSecret();
        assertThat(TotpGenerator.verify(secret, null)).isFalse();
        assertThat(TotpGenerator.verify(secret, "12345")).isFalse();  // too short
        assertThat(TotpGenerator.verify(secret, "1234567")).isFalse(); // too long
        assertThat(TotpGenerator.verify(secret, "12345a")).isFalse();  // not all digits
    }

    @Test
    void provisioningUriPercentEncodesTheAccountNameAndCarriesTheSecret() {
        String secret = TotpGenerator.generateSecret();
        String uri = TotpGenerator.provisioningUri(secret, "Finora Admin", "jane admin@example.com");

        assertThat(uri).startsWith("otpauth://totp/Finora%20Admin:jane%20admin%40example.com");
        assertThat(uri).contains("secret=" + secret);
        assertThat(uri).contains("issuer=Finora%20Admin");
        assertThat(uri).contains("algorithm=SHA1&digits=6&period=30");
    }
}
