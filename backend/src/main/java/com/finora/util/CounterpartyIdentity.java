package com.finora.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a stable identity key for the entity on the other side of a transaction.
 *
 * <h2>Why the VPA and not the name</h2>
 *
 * <p>{@code MerchantNormalizationEngine} groups by the first significant token of the description --
 * "a deliberately simple heuristic", by its own class doc, whose misses "are exactly what the manual
 * merge merchants feature exists to fix by hand". That weakness is load-bearing elsewhere: it is the
 * reason the merchant keyword retry has to be gated on an admin-approved lifecycle at all.
 *
 * <p>A UPI VPA is a far stronger key, and the difference is measurable. Keying the real corpus on the
 * VPA local-part concentrated the unresolved value into a median of <b>2 counterparties per
 * statement to explain 80% of it</b>, and counterparties seen three or more times accounted for
 * 79.8% of unresolved value against 14.9% for one-offs. A payee's NAME is truncated differently by
 * every bank and every statement layout; their VPA is not.
 *
 * <h2>What the key is, and what it is not</h2>
 *
 * <p>Keys are prefixed by strength so a caller can tell them apart rather than treating a guess as
 * an identity:
 *
 * <ul>
 *   <li>{@code vpa:<local-part>} -- strong. The handle is dropped deliberately: the same person
 *       collecting on {@code @ybl} and {@code @paytm} with one phone number is one counterparty, and
 *       keeping the handle would split them.</li>
 *   <li>{@code name:<token>} -- weak, and only as good as the extraction. Two spellings of one payee
 *       will not merge.</li>
 *   <li>{@code ""} -- nothing derivable. Not an error.</li>
 * </ul>
 *
 * <p>This is NOT entity resolution. It over-splits far more often than it over-merges, which is the
 * safe direction to be wrong in: an over-split shows a user two rows to confirm, an over-merge
 * silently attributes one person's money to another. Every concentration figure measured with it is
 * therefore a LOWER bound.
 */
public final class CounterpartyIdentity {

    private CounterpartyIdentity() {}

    /**
     * Local-part@handle. The local part is what identifies the human; the handle is their PSP.
     *
     * <p>The charset deliberately EXCLUDES the hyphen even though a real VPA may contain one.
     * Narrations use "-" as a segment delimiter far more often than a VPA uses it as a character,
     * so allowing it made the match run backwards over the delimiter and swallow the payee name --
     * "UPI-SUNIL VERMA-sampleuser@ybl" keyed as {@code vpa:verma-sampleuser}, which is worse than
     * useless: it re-introduces exactly the name-truncation instability the VPA key exists to
     * escape. Caught by CounterpartyIdentityTest before this shipped.
     */
    private static final Pattern VPA = Pattern.compile("([A-Za-z0-9._]{2,})@([A-Za-z][A-Za-z0-9]{1,})");

    /** Rail words, plumbing and reference noise -- present in nearly every narration, identifying in none. */
    private static final Pattern NOISE = Pattern.compile(
            "(?i)^(UPI|NEFT|IMPS|RTGS|TRF|TRANSFER|PAYMENT|PAY|PAID|TO|FROM|BY|REF|RRN|TXN|MB|IB|NB"
            + "|NET|MOB|ONLINE|SELF|OWN|COLLECT|INTENT|CR|DR|ACH|NACH|ECS)$");

    private static final Pattern SEGMENTS = Pattern.compile("[\\-/_|:]+");
    private static final Pattern NON_LETTERS = Pattern.compile("[^A-Za-z]+");

    /**
     * Hard cap on a returned key, matching {@code transactions.counterparty_key VARCHAR(120)} in
     * V139. Not decoration -- without it this method is bounded by the DESCRIPTION, not by 120.
     *
     * <p>{@code meaningfulPart} concatenates every surviving word of a segment, and {@link
     * #SEGMENTS} only splits on {@code - / _ | :}, so a space-only narration is one segment and its
     * key is the whole narration. Measured against the real shapes: a 123-character IMPS line keys
     * to 118 characters -- two under the column -- and a 500-character description (the width
     * {@code transactions.description} itself accepts) keys to 505. This pipeline deliberately
     * joins wrapped continuation rows into a single narration, so the long end of that range is
     * ordinary input, not a pathological one. Uncapped, the first such row would fail its INSERT,
     * and in {@code ImportService.confirm} that fails the user's entire statement.
     *
     * <p>Truncating rather than returning {@code ""}: two rows carrying the same over-long
     * narration truncate identically, so grouping -- the only thing this key is for -- survives.
     * A key AT the cap is near-certainly a whole narration rather than a name, which is a poor
     * identity but an honest one; {@link #isStrong} already refuses to treat any {@code name:} key
     * as presentable identity, so nothing user-facing can mistake it for a resolved counterparty.
     */
    public static final int MAX_KEY_LENGTH = 120;

    /**
     * A stable key for the counterparty, or {@code ""} when the narration carries nothing usable.
     *
     * <p>A reference-heavy segment is skipped rather than keyed on: a token carrying four or more
     * digits is an RRN or an account fragment, and keying on one would make every transaction its
     * own counterparty -- the exact opposite of what this is for.
     */
    public static String keyOf(String description) {
        if (description == null || description.isBlank()) return "";

        Matcher vpa = VPA.matcher(description);
        if (vpa.find()) {
            String local = vpa.group(1).toLowerCase();
            // A bare numeric local part is a phone number, which is a perfectly good identity; a
            // local part that is only punctuation is not.
            if (!local.replaceAll("[._-]", "").isEmpty()) return cap("vpa:" + local);
        }

        String best = "";
        for (String segment : SEGMENTS.split(description)) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;
            if (countDigits(trimmed) >= 4) continue;               // reference/account fragment
            String letters = String.join(" ", NON_LETTERS.split(trimmed)).trim();
            if (letters.isEmpty()) continue;
            String candidate = meaningfulPart(letters);
            if (candidate.length() > best.length()) best = candidate;
        }
        return best.isEmpty() ? "" : cap("name:" + best.toLowerCase());
    }

    /** Applies {@link #MAX_KEY_LENGTH}. Both key shapes go through here: a VPA local part is
     *  {@code [A-Za-z0-9._]{2,}} with no upper bound of its own, so it is no safer than a name. */
    private static String cap(String key) {
        return key.length() <= MAX_KEY_LENGTH ? key : key.substring(0, MAX_KEY_LENGTH);
    }

    /** True when this key came from a VPA, i.e. is safe to treat as an identity rather than a guess. */
    public static boolean isStrong(String key) {
        return key != null && key.startsWith("vpa:");
    }

    private static String meaningfulPart(String letters) {
        StringBuilder sb = new StringBuilder();
        for (String word : letters.split("\\s+")) {
            if (word.length() < 2 || NOISE.matcher(word).matches()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(word);
        }
        return sb.toString().trim();
    }

    private static int countDigits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) n++;
        }
        return n;
    }
}
