package com.finora.security.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/**
 * SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). RFC 6238 TOTP
 * (HMAC-SHA1, 30-second step, 6 digits) -- the algorithm every mainstream authenticator app
 * (Google Authenticator, Authy, 1Password, Apple's built-in one) already implements, so enrolling
 * needs nothing beyond scanning a QR code or typing a secret. Implemented directly rather than
 * pulling in a library: the algorithm is small, fully specified, and this is the only place in the
 * codebase that needs it -- see {@code EncryptionService}'s own class doc for the parallel
 * reasoning about AES-GCM ("this class deliberately knows nothing about where keys live").
 *
 * <h2>SHA-1, deliberately, despite the name</h2>
 *
 * TOTP's security rests on HMAC's properties (keyed, one-way, and the code space is only 10^6
 * regardless of hash), not on SHA-1's collision resistance -- there is no collision attack that
 * applies to how HMAC-SHA1 is used here. RFC 6238 specifies SHA-1 as the default and it is what
 * every mainstream authenticator app assumes when scanning a QR code with no algorithm parameter;
 * asking for SHA-256 would silently break compatibility with most of them for no real security
 * gain.
 *
 * <h2>Base32, not Base64, for the secret</h2>
 *
 * The provisioning URI ({@link #provisioningUri}) and every authenticator app's manual-entry field
 * expect Base32 (RFC 4648) -- it's case-insensitive and excludes visually ambiguous characters
 * (0/O, 1/I/l), which matters for a value a person might actually have to type. Java has no
 * built-in Base32 codec (unlike Base64), so encode/decode are implemented here too.
 */
public final class TotpGenerator {

    private static final String ALGORITHM = "HmacSHA1";
    private static final int CODE_DIGITS = 6;
    private static final int TIME_STEP_SECONDS = 30;
    /** Secret length in bytes -- 160 bits, RFC 4226's recommended HOTP/TOTP key size. */
    private static final int SECRET_BYTES = 20;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TotpGenerator() {}

    /** A fresh random secret, Base32-encoded -- store it encrypted (see AdminMfaService), never
     *  in this form. */
    public static String generateSecret() {
        byte[] raw = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(raw);
        return base32Encode(raw);
    }

    /**
     * {@code otpauth://totp/...} -- what a QR code encodes. {@code issuer} and {@code accountName}
     * both appear in the authenticator app's list, which is the only place a user distinguishes
     * one enrolled account from another; both are also percent-encoded, since either can contain
     * spaces (issuer is always "Finora Admin" here, but accountName is a real email address).
     */
    public static String provisioningUri(String base32Secret, String issuer, String accountName) {
        String label = urlEncode(issuer) + ":" + urlEncode(accountName);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + CODE_DIGITS + "&period=" + TIME_STEP_SECONDS;
    }

    /** The code for right now -- exists for tests that need a genuinely valid code to hand
     *  {@link #verify} rather than reaching across the package boundary for
     *  {@link #generateForStep}. Not used by any real login/enrollment flow: Finora never
     *  generates a code itself, since the whole point of TOTP is that the user's own
     *  authenticator app does. */
    public static String currentCode(String base32Secret) {
        return generateForStep(base32Secret, Instant.now().getEpochSecond() / TIME_STEP_SECONDS);
    }

    /**
     * Checks a user-supplied code against the current time step and one step on either side, to
     * absorb ordinary clock drift between the server and the phone that generated it -- a real
     * three-step (90-second) window that a hard-coded "current step only" check would routinely
     * and confusingly reject valid codes within.
     */
    public static boolean verify(String base32Secret, String code) {
        if (code == null || !code.matches("\\d{" + CODE_DIGITS + "}")) return false;
        long currentStep = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        for (long step = currentStep - 1; step <= currentStep + 1; step++) {
            if (generateForStep(base32Secret, step).equals(code)) return true;
        }
        return false;
    }

    // Package-private (not private) specifically so TotpGeneratorTest can verify this against
    // RFC 6238 Appendix B's own published test vectors at their exact, fixed step numbers --
    // verify()/generateSecret() alone give no way to test hand-rolled HMAC/truncation logic
    // against a known-correct reference without either mocking a clock or exposing this.
    static String generateForStep(String base32Secret, long step) {
        byte[] key = base32Decode(base32Secret);
        byte[] counter = new byte[8];
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (step & 0xFF);
            step >>= 8;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] hash = mac.doFinal(counter);

            // Dynamic truncation, RFC 4226 §5.3 -- takes 4 bytes starting at an offset the hash's
            // own last nibble picks, so the extracted bytes vary with the input instead of always
            // being a fixed slice.
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int code = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", code);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA1 not available", e);
        }
    }

    // Package-private for the same reason generateForStep is: TotpGeneratorTest needs to Base32-
    // encode RFC 6238's raw ASCII test-vector key before it can exercise generateForStep with it.
    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int bits = 0, value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((value >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((value << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    // Package-private for the same reason base32Encode is: direct round-trip testing.
    static byte[] base32Decode(String encoded) {
        String clean = encoded.trim().toUpperCase(Locale.ROOT).replace("=", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bits = 0, value = 0;
        for (char c : clean.toCharArray()) {
            int index = BASE32_ALPHABET.indexOf(c);
            if (index < 0) continue; // ignore stray formatting characters (spaces, dashes)
            value = (value << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((value >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
