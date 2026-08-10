package com.finora.imports.evidence;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Token-overlap text similarity for description/semantic-context comparison -- design §2.2/§2.3
 * name this as a needed signal ("descriptionSimilarity(A, B) > threshold_D") without specifying an
 * algorithm; nothing reusable existed in the codebase for it (see {@code ConfidenceEngine}'s own
 * note that description similarity is a deferred signal), so this is net new.
 *
 * <p>Deliberately order-insensitive (a Jaccard set ratio, not an edit distance): OCR frequently
 * reorders or drops whitespace within a recognised line, and a wrapped description split across two
 * text runs by one acquisition source but not the other should still score as similar to its
 * un-wrapped counterpart as long as the same words are present.
 */
final class TextSimilarity {

    private TextSimilarity() {
    }

    /** @return 0.0 for no shared tokens (including when either input has none), up to 1.0 for an
     *          identical token set, case- and punctuation-insensitive. */
    static double tokenOverlapRatio(String a, String b) {
        Set<String> tokensA = tokenize(a);
        Set<String> tokensB = tokenize(b);
        if (tokensA.isEmpty() || tokensB.isEmpty()) {
            return 0.0;
        }
        long intersection = tokensA.stream().filter(tokensB::contains).count();
        long union = tokensA.size() + tokensB.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
    }
}
