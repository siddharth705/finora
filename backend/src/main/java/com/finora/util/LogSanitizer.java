package com.finora.util;

import java.util.regex.Pattern;

/**
 * Strips control characters from a value on its way into a log line.
 *
 * <p>CodeQL (java/log-injection), 2026-09-04: an uploaded statement's original filename, and an
 * OAuth callback's {@code error} query parameter, both reach {@code log.error}/{@code log.warn}
 * calls via SLF4J's {@code {}} parameterization -- safe against format-string injection (the
 * format string itself is a compile-time constant, never user-controlled), but not against a
 * literal {@code \r}/{@code \n} inside the VALUE itself. A filename like
 * {@code "statement.pdf\r\n[ERROR] fake log line"} would appear in the log file as two lines,
 * indistinguishable from a genuine second log entry, to anyone grepping or scanning it later.
 *
 * <p>Unlike {@link EmailMasking}/{@link PhoneMasking}, this does not hide or shorten the value --
 * the filename itself is exactly what the surrounding log message needs to be diagnosable. It
 * only neutralises the specific characters that could forge a fake line, leaving everything else,
 * including non-ASCII text, untouched.
 */
public final class LogSanitizer {

    private LogSanitizer() {}

    // The C0 control range (0x00-0x1F) plus DEL (0x7F) -- CR/LF are what actually forges a fake
    // log line, but every other control character in this range is equally out of place in a
    // filename or an OAuth error code, and equally worth neutralising rather than allowlisting
    // CR/LF alone and hoping nothing else in this range causes trouble in whatever log viewer or
    // downstream log-shipping pipeline eventually reads the file.
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\x00-\\x1F\\x7F]");

    /**
     * @return {@code value} with every control character replaced by {@code '?'}, preserving the
     *         original length so a truncated-looking filename doesn't itself look like a second
     *         finding. {@code null} in, {@code null} out -- SLF4J already renders a null argument
     *         as the literal text {@code "null"}, which this must not turn into an empty string
     *         instead.
     */
    public static String sanitize(String value) {
        if (value == null) return null;
        return CONTROL_CHARACTERS.matcher(value).replaceAll("?");
    }
}
