package com.finora.util;

/**
 * Masks a phone number for display on an OTP-entry screen -- "a code was sent to +91••••••705,"
 * not the number in full. Reusable capability, not a point patch: before this existed, neither
 * VerifyPhone.tsx (the main app's or the admin portal's) showed which number a code went to at
 * all, which matters more than it sounds -- the person's actual phone might be stored with a stale
 * or wrong country code (see the admin-onboarding incident this was built to prevent a repeat of:
 * a number stored without its country code caused Twilio to silently reject every send, and the
 * generic "a code was sent to your phone" message gave no way to notice).
 */
public final class PhoneMasking {

    private PhoneMasking() {}

    /** Number of trailing digits left visible -- enough to recognize "yes, that's my phone"
     *  without exposing enough of the number to be useful to anyone else who sees the screen. */
    private static final int VISIBLE_SUFFIX_LENGTH = 3;

    /**
     * @return e.g. {@code "+919000000705"} -&gt; {@code "+•••••••••705"}; {@code null} for a
     *         null/blank input (nothing to mask); the original value unchanged if it's too short
     *         to mask meaningfully (masking would either reveal everything or nothing useful).
     *         Deliberately does not attempt to split out and reveal the country code separately
     *         (that needs a real E.164 parsing library to do correctly in general) -- but keeping
     *         the leading {@code +} itself visible when present is still enough to catch the
     *         actual bug class this exists for: a number stored without its country code at all
     *         shows as {@code "•••••••705"} (no leading +), visually distinct from a properly
     *         formatted one, even with every digit otherwise masked.
     */
    public static String mask(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) return null;

        boolean hasCountryCodePrefix = phoneNumber.startsWith("+");
        String prefix = hasCountryCodePrefix ? "+" : "";
        String digits = hasCountryCodePrefix ? phoneNumber.substring(1) : phoneNumber;

        if (digits.length() <= VISIBLE_SUFFIX_LENGTH) return phoneNumber;

        String visible = digits.substring(digits.length() - VISIBLE_SUFFIX_LENGTH);
        String masked = "•".repeat(digits.length() - VISIBLE_SUFFIX_LENGTH);
        return prefix + masked + visible;
    }
}
