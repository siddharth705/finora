package com.finora.imports.ownership;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * docs/proposals/account-ownership-intelligence-proposal.md §3.1: compares a statement's extracted
 * holder name against a Finora profile name. A token-set comparison, not edit distance -- real
 * variance in how a name prints (initials, honorifics, word order, a second joint holder) looks
 * nothing like a typo, and edit distance would either miss it or need per-case tuning to catch it.
 *
 * <p>Deliberately does not split the extracted name into separate candidate holders before matching
 * -- see the design doc's §3.1 point 3 for why a naive split is wrong: "RAHUL AND PRIYA SHARMA" split
 * into ["RAHUL", "PRIYA SHARMA"] would separate the shared surname from the first name it belongs to,
 * and neither half alone would satisfy a profile of "Rahul Sharma". Instead, the whole extracted
 * string is tokenized as one set (with "AND"/"OR" dropped as connector words, and "&" already absent
 * from a letters-only split), and a match requires every profile-name token to be satisfied by SOME
 * token in that set -- which handles a joint holder correctly without needing to know where the
 * joiner is.
 */
public final class HolderNameMatcher {

    private HolderNameMatcher() {}

    private static final Set<String> CONNECTOR_WORDS = Set.of("AND", "OR");

    /**
     * @return true if every token in {@code profileName} is satisfied (exact match, or as an
     * initial) by some token extracted from {@code extractedHolderName}. False if either name is
     * blank -- there is nothing to compare, and "nothing to compare" is a caller-level concern
     * ({@code NO_HOLDER_FOUND}), not a match.
     */
    public static boolean isLikelyMatch(String extractedHolderName, String profileName) {
        Set<String> extractedTokens = tokensOf(extractedHolderName);
        Set<String> profileTokens = tokensOf(profileName);
        if (extractedTokens.isEmpty() || profileTokens.isEmpty()) return false;

        for (String profileToken : profileTokens) {
            boolean satisfied = extractedTokens.stream().anyMatch(t -> tokenMatches(profileToken, t));
            if (!satisfied) return false;
        }
        return true;
    }

    private static Set<String> tokensOf(String name) {
        if (name == null) return Set.of();
        return Arrays.stream(name.trim().toUpperCase(Locale.ROOT).split("[^A-Z]+"))
                .filter(t -> !t.isBlank() && !CONNECTOR_WORDS.contains(t))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Equal, or one is a single-letter initial of the other. Not commutative-by-accident -- both
     *  directions are checked because either side (statement or profile) might be the abbreviated
     *  one ("R Sharma" on the statement vs. "Rahul Sharma" on the profile, or the reverse). */
    private static boolean tokenMatches(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() == 1) return b.startsWith(a);
        if (b.length() == 1) return a.startsWith(b);
        return false;
    }
}
