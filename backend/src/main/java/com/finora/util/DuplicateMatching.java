package com.finora.util;

import java.util.Locale;

/**
 * The one definition of "are these two transaction descriptions the same description" used by
 * every duplicate-detection path.
 *
 * <h2>Why this exists as a shared class rather than a local helper</h2>
 *
 * <p>A2 (two-pass mobile audit, 2026-09-01; see
 * docs/project-management/plans/mobile-correctness-trust-roadmap.md, Track A). Duplicate detection
 * asks the same question in three places that must agree: {@code
 * TransactionRepository}'s {@code findPotentialDuplicates*} JPQL, {@code DuplicateIndex.key}'s
 * in-memory index, and {@code ReconciliationService.duplicateKey}'s grouping pass. {@code
 * DuplicateIndexIT} asserts the first two are equivalent against a real Postgres, and a divergence
 * between them does not fail -- it silently changes which duplicates get surfaced.
 *
 * <p>Two extractions of the same underlying bank line (a CSV export vs. a re-scraped PDF, or a bank
 * that shifts capitalization between monthly exports) can differ by nothing more than case or
 * surrounding whitespace, so all three fold that away. The subtlety, and the reason this is not
 * inlined as {@code description.trim().toLowerCase()} at each site:
 *
 * <p><b>Java's {@link String#trim()} and SQL's {@code TRIM()} are not the same function.</b> Java
 * strips every character {@code <= U+0020}, tabs and newlines included; SQL {@code TRIM()} strips
 * the space character {@code U+0020} and nothing else. A description carrying a leading tab -- which
 * the CSV path really can produce, since {@code CsvParser.firstNonBlank} returns the cell value
 * untrimmed -- would therefore be normalized by the Java paths and left alone by the SQL one, and
 * the two would disagree about whether it is a duplicate. That divergence class did not exist while
 * both sides were exact equality, so it would have been introduced by the very change meant to make
 * them agree more often.
 *
 * <p>This method deliberately matches the SQL definition rather than the more aggressive Java one:
 * the JPQL {@code TRIM()} is the constraint that cannot be widened without a Postgres-specific
 * {@code btrim} call (JPQL's own {@code TRIM} takes a single trim character), and an equivalence
 * that is exact and testable is worth more here than folding away one extra whitespace class. A
 * tab-padded description is therefore still not matched to its untabbed twin -- exactly as it was
 * not before this change, so no regression -- and both paths agree that it isn't.
 */
public final class DuplicateMatching {

    private DuplicateMatching() {}

    /**
     * A description reduced to the form duplicate detection compares.
     *
     * <p>Must stay in lockstep with the {@code LOWER(TRIM(...))} in {@code TransactionRepository}'s
     * duplicate queries: spaces only (never {@link String#trim()}, see this class's own comment) and
     * {@link Locale#ROOT} (the database is initialized {@code en_US.utf8}, whose {@code LOWER()}
     * agrees with {@code Locale.ROOT} on every character reachable here).
     *
     * @param description never null -- every caller guards for null before reaching this, because a
     *                    null description means "no identity", which is a different answer from
     *                    "identity is the empty string"
     */
    public static String normalizeDescription(String description) {
        int start = 0;
        int end = description.length();
        while (start < end && description.charAt(start) == ' ') start++;
        while (end > start && description.charAt(end - 1) == ' ') end--;
        return description.substring(start, end).toLowerCase(Locale.ROOT);
    }
}
