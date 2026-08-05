package com.finora.util;

/**
 * The single place a phone number is canonicalized before it is stored, and the single place two
 * phone numbers are compared.
 *
 * <p><b>Why this exists.</b> {@code User.phoneNumber} had two writers and only one of them
 * normalized. Registration went through {@code AuthService.normalizePhoneNumber} (a
 * {@code private static} method, so structurally unreachable from anywhere else) and stored E.164;
 * the admin support-edit path in {@code AdminUserService.updateProfile} assigned whatever the
 * admin typed, verbatim. Two consequences, both real:
 *
 * <ul>
 *   <li><b>Permanent lockout.</b> Firebase's {@code phone_number} claim is always E.164. The
 *       verification check compares digits only, so a stored bare {@code "9999999999"} yields
 *       {@code "9999999999"} against Firebase's {@code "919999999999"} — never equal, so phone
 *       verification can never succeed again. {@code PhoneVerificationFilter} then 403s every
 *       endpoint except {@code /auth/**}, {@code /phone/**}, {@code GET /setup/status} and
 *       {@code GET /users/me}, and there is no self-service path back.</li>
 *   <li><b>Uniqueness bypass.</b> The duplicate check queries the literal column, so
 *       {@code "9999999999"} and {@code "+919999999999"} are two different strings and both rows
 *       are allowed to exist — reintroducing precisely the ambiguity V13's comment says its index
 *       prevents, that "a findByPhoneNumber lookup expecting at most one match" can rely on.</li>
 * </ul>
 *
 * <p>The lesson generalizes past this one field: a normalization rule that lives as a private
 * method on the class that happens to have needed it first is a rule the second writer cannot
 * reuse even when they want to. For every normalized field, the question worth asking is how many
 * places write it and whether all of them go through the same normalizer — so this is a public
 * utility, and {@code AdminUserServicePhoneNormalizationTest} asserts both writers agree.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /**
     * Canonicalizes a phone number to E.164 ({@code "+919999999999"}) so every stored number has
     * the same shape, rather than relying on {@link #sameNumber} to paper over inconsistent
     * storage at every point of comparison.
     *
     * <p>Accepts either a leading {@code "+"} or a bare 10-15 digit string, matching what
     * {@code RegisterRequest}'s own {@code @Pattern} permits. A bare 10-digit number is assumed
     * Indian — the only market this app currently supports.
     *
     * <p>Returns null for null input so a caller doing "only update when supplied" keeps that
     * shape; a blank string normalizes to blank rather than to a bare {@code "+"}, so a caller
     * that treats blank as "not supplied" still sees blank.
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return trimmed;
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return trimmed;
        if (trimmed.startsWith("+")) return "+" + digits;
        if (digits.length() == 10) return "+91" + digits;
        return "+" + digits;
    }

    /**
     * Digit-only equality, for comparing a number from an external source (Firebase's E.164
     * claim) against a stored one that may predate {@link #normalize} being applied at that
     * write site.
     *
     * <p>This deliberately compares digits rather than normalized forms: a legacy row holding a
     * bare {@code "9999999999"} must still match Firebase's {@code "+919999999999"} for that
     * user, or normalizing new writes would lock out exactly the accounts written before the fix.
     * Normalization closes the hole going forward; this keeps existing rows working.
     */
    public static boolean sameNumber(String a, String b) {
        if (a == null || b == null) return false;
        String digitsA = a.replaceAll("[^0-9]", "");
        String digitsB = b.replaceAll("[^0-9]", "");
        if (digitsA.isEmpty() || digitsB.isEmpty()) return false;
        if (digitsA.equals(digitsB)) return true;
        // A bare 10-digit Indian number against the same number stored with its country code.
        // normalize() makes both sides E.164 for anything written from now on, so this only
        // matters for rows written before that -- see the method doc above.
        return withIndianCountryCode(digitsA).equals(withIndianCountryCode(digitsB));
    }

    private static String withIndianCountryCode(String digits) {
        return digits.length() == 10 ? "91" + digits : digits;
    }
}
