package com.finora.util;

/**
 * Masks an email address on its way into a log line.
 *
 * <p>The counterpart to {@link PhoneMasking}, and it exists for a narrower reason than that class
 * does. PhoneMasking is a DISPLAY capability -- it renders a number onto an OTP screen for the
 * person who owns it. This is a LOGGING capability: nobody sees its output but an operator reading
 * the application log, and the whole point is that the operator should not be reading customer
 * email addresses there.
 *
 * <p>Why mask rather than drop the field entirely. An email send that fails needs to be
 * diagnosable, and "a send to somebody failed" is not a diagnosis -- an operator needs to tell one
 * failing recipient from another across a run of log lines, and to recognise a whole-domain
 * failure (every {@code @corporate-domain.com} bouncing) as different from one bad address. The
 * domain is what carries that signal and it is not, on its own, personal data; the local part is
 * the identifying half, so that is the half that goes.
 *
 * <p>Deliberately NOT a hash. A hash is stable across lines, which is genuinely useful for
 * correlation, but a hashed email is trivially reversible by dictionary against any address you
 * already suspect -- which is exactly the position someone reading logs to identify a user is in.
 * Where a stable correlation handle is actually needed, the caller already has the user id.
 */
public final class EmailMasking {

    private EmailMasking() {}

    /** Leading characters of the local part left visible -- enough to tell two failing recipients
     *  apart at a glance without reconstructing either address. */
    private static final int VISIBLE_PREFIX_LENGTH = 1;

    /**
     * @return e.g. {@code "priya.sharma@example.com"} -&gt; {@code "p•••••••••••@example.com"};
     *         {@code null} for a null/blank input (nothing to mask). A value with no {@code @} is
     *         masked in full rather than passed through: this method is called on things that are
     *         SUPPOSED to be addresses, so a value that is not one is a malformed address or the
     *         wrong variable, and both are worse to print than to hide. A local part at or under
     *         the visible-prefix length is likewise masked in full, since revealing it would
     *         reveal the whole thing.
     */
    public static String mask(String email) {
        if (email == null || email.isBlank()) return null;

        int at = email.lastIndexOf('@');
        if (at < 0) return "•".repeat(email.length());

        String localPart = email.substring(0, at);
        String domain = email.substring(at);

        if (localPart.length() <= VISIBLE_PREFIX_LENGTH) {
            return "•".repeat(localPart.length()) + domain;
        }
        return localPart.substring(0, VISIBLE_PREFIX_LENGTH)
                + "•".repeat(localPart.length() - VISIBLE_PREFIX_LENGTH)
                + domain;
    }
}
