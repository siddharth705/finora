package com.finora.imports;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Reconstructs which observation in a same-calendar-day cluster of running-balance rows was
 * chronologically first or last, from the balance chain itself rather than file line order.
 *
 * Why this needs to exist at all: a real bank export's line order within a single day does NOT
 * reliably indicate transaction order -- confirmed against a real PNB ONE statement, which lists
 * newest-first, where the statement's earliest date had a 7-transaction cluster whose first-LISTED
 * row was actually that day's chronologically LAST transaction. Assuming file order silently
 * produces a wrong opening/closing balance whenever a statement's boundary date has more than one
 * transaction.
 *
 * Extracted as a shared utility -- rather than duplicated per import format -- specifically
 * because that duplication is what let this exact bug exist in two places at once and only get
 * fixed in one: {@code com.finora.imports.StatementValidator} (CSV path) and
 * {@code com.finora.imports.pdf.PdfPreviewGenerator} (PDF path) each had their own independent
 * "earliest/latest by file position" implementation of this same idea, and fixing the CSV one
 * first would have left the PDF one silently wrong. Both now delegate here instead.
 */
public final class BalanceChainUtil {

    private BalanceChainUtil() {}

    /** Minimal shape this utility needs from a same-day balance observation, regardless of which
     *  import format's own richer record actually holds the rest of that row's data. */
    public interface ChainLink {
        /** This transaction's signed effect on the balance: positive for income/credit, negative
         *  for expense/debit. */
        BigDecimal signedAmount();
        /** The running balance AS REPORTED immediately after this transaction. */
        BigDecimal balanceAfter();
    }

    /** Among a single calendar day's observations, finds the one with no predecessor in the
     *  day's chain -- no other same-day observation's balanceAfter matches this one's implied
     *  pre-transaction balance (balanceAfter minus signedAmount) -- meaning nothing else that day
     *  flows into it. That makes it the day's true first transaction. */
    public static <T extends ChainLink> T first(List<T> sameDayGroup) {
        if (sameDayGroup.size() == 1) return sameDayGroup.get(0);
        for (T candidate : sameDayGroup) {
            BigDecimal impliedBefore = candidate.balanceAfter().subtract(candidate.signedAmount());
            boolean hasPredecessor = sameDayGroup.stream()
                    .anyMatch(other -> other != candidate && other.balanceAfter().compareTo(impliedBefore) == 0);
            if (!hasPredecessor) return candidate;
        }
        // No clean chain found (a rounding difference breaks the link, or the day's observations
        // genuinely aren't one contiguous sequence) -- fall back to whichever implies the
        // earliest starting point. At worst this is no less correct than a file-position guess,
        // and still right whenever a chain does exist but this loop's exact match missed it.
        return sameDayGroup.stream()
                .min(Comparator.comparing(o -> o.balanceAfter().subtract(o.signedAmount())))
                .orElse(sameDayGroup.get(0));
    }

    /** Mirror of {@link #first} for a statement's latest date: finds the observation with no
     *  successor (no other same-day observation's implied pre-transaction balance matches this
     *  one's actual balanceAfter), making it the day's true last transaction. */
    public static <T extends ChainLink> T last(List<T> sameDayGroup) {
        if (sameDayGroup.size() == 1) return sameDayGroup.get(0);
        for (T candidate : sameDayGroup) {
            boolean hasSuccessor = sameDayGroup.stream().anyMatch(other -> {
                if (other == candidate) return false;
                return other.balanceAfter().subtract(other.signedAmount()).compareTo(candidate.balanceAfter()) == 0;
            });
            if (!hasSuccessor) return candidate;
        }
        return sameDayGroup.stream().max(Comparator.comparing(ChainLink::balanceAfter)).orElse(sameDayGroup.get(0));
    }
}
