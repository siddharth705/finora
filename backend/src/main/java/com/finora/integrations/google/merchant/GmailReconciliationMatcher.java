package com.finora.integrations.google.merchant;

import com.finora.dto.ImportDto.DuplicateMatch;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.util.CategoryRules;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * C6.4, staging-time direction: is a Gmail receipt about to be staged the same purchase as a bank
 * transaction already sitting confirmed in the ledger — design proposal §3.
 *
 * <h2>Why this can't reuse {@link com.finora.imports.DuplicateDetector} as-is</h2>
 *
 * {@code DuplicateDetector} requires exact description equality, which is correct for its own job
 * (CSV/PDF re-import) but structurally cannot fire here: a receipt's description is the merchant
 * domain ({@code "amazon.in"}), a bank line reads {@code "AMZN MKTPLACE 4521"}, and those two
 * strings are never equal. This class matches on amount (exact — the one signal that genuinely
 * should agree) plus a date window (a receipt's send date and a bank's settlement date routinely
 * differ by a day or more) plus merchant-name similarity (comparing the domain's brand token
 * against {@link CategoryRules#extractMerchant}'s reduction of the bank description, the same
 * reduction {@code MerchantNormalizationEngine} already trusts for grouping merchant variants).
 *
 * <h2>Reported as {@code DuplicateMatch}, not a new type</h2>
 *
 * {@code confidence = "LIKELY"} distinguishes this from {@code DuplicateDetector}'s
 * {@code "EXACT"} — {@code DuplicateMatch}'s own doc comment named this exact gap ("a fuzzier
 * tier... would create a real spectrum") before it existed. {@code DuplicateReview.tsx} already
 * renders whatever confidence string it's handed, so no frontend change is needed for a second
 * tier to appear.
 */
@Component
public class GmailReconciliationMatcher {

    /** A receipt's stated date and a bank's settlement date routinely differ by a day or more;
     *  wide enough to catch that, narrow enough that an unrelated same-amount transaction three
     *  weeks later doesn't get pulled in as a candidate. Public: {@code ReconciliationService}'s
     *  cross-source pass (see {@link #findMatchAmongTransactions}) needs the same window to score
     *  a match's date_decay, and duplicating the value would let the two drift apart silently. */
    public static final int DATE_WINDOW_DAYS = 3;

    /** Normalized Levenshtein similarity (1.0 = identical) a receipt's brand token must reach
     *  against some token of the bank description to count as the same business. 0.6 passes
     *  "amazon" vs "amzn" (2 edits / 6 chars ≈ 0.67) without passing unrelated short tokens. */
    private static final double SIMILARITY_THRESHOLD = 0.6;

    /** Below this, a brand token is too short for edit-distance similarity to mean anything —
     *  most any two short tokens are "close" by raw edit count. */
    private static final int MIN_BRAND_TOKEN_LENGTH = 3;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public GmailReconciliationMatcher(TransactionRepository transactionRepository, AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Matches against every LIVE account's transactions, excluding a soft-deleted account's rows
     * which the unscoped query would otherwise keep matching against forever (see
     * {@code TransactionRepository.findByUserIdAndAccountIdIn}'s own doc comment). This search is
     * deliberately cross-account by design -- a Gmail receipt isn't tied to one account until it's
     * matched -- only "including a deleted account's history forever" is what's being fixed here.
     */
    public Optional<DuplicateMatch> findMatch(UUID userId, LocalDate receiptDate,
                                               java.math.BigDecimal amount, String merchantDomain) {
        String brandToken = brandTokenOf(merchantDomain);
        if (brandToken.length() < MIN_BRAND_TOKEN_LENGTH) return Optional.empty();

        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream().map(Account::getId).toList();
        if (liveAccountIds.isEmpty()) return Optional.empty();

        List<Transaction> candidates = transactionRepository.findCandidatesForGmailReconciliationAndAccountIdIn(
                userId, amount, receiptDate.minusDays(DATE_WINDOW_DAYS), receiptDate.plusDays(DATE_WINDOW_DAYS),
                liveAccountIds);
        if (candidates.isEmpty()) return Optional.empty();

        List<Transaction> matches = candidates.stream()
                .filter(t -> bestTokenSimilarity(brandToken, t.getDescription()) >= SIMILARITY_THRESHOLD)
                .sorted(Comparator.comparingDouble(
                                (Transaction t) -> bestTokenSimilarity(brandToken, t.getDescription()))
                        .reversed()
                        .thenComparing(t -> Math.abs(t.getTxnDate().toEpochDay() - receiptDate.toEpochDay())))
                .toList();
        if (matches.isEmpty()) return Optional.empty();

        Transaction best = matches.get(0);
        return Optional.of(new DuplicateMatch(
                best.getId(),
                best.getAccountId(),
                best.getTxnDate(),
                best.getDescription(),
                best.getAmount(),
                best.getTxnType() == null ? null : best.getTxnType().name(),
                best.getCreatedAt(),
                matches.size(),
                "LIKELY",
                "Same amount around this date, and the merchant on this transaction looks like "
                        + "the same business as your Gmail receipt."));
    }

    /**
     * The confirmed-transaction-vs-transaction sibling of {@link #findMatch} -- used by {@code
     * ReconciliationService}'s Gmail cross-source pass to find a persisted CSV/PDF-sourced
     * transaction that a persisted {@code GMAIL_IMPORT} transaction appears to duplicate, once
     * both sides are real ledger rows rather than a receipt still being staged.
     *
     * <p>Reduces BOTH descriptions through {@link CategoryRules#extractMerchant} and compares
     * every token pair, rather than {@link #findMatch}'s domain-token split -- a confirmed Gmail
     * transaction's description is whatever {@code descriptionFor(receipt)} chose at staging time
     * (a counterparty name when the receipt had one, a bare domain otherwise), so the "text before
     * the first dot is the brand" assumption {@link #brandTokenOf} makes does not hold once the
     * row is just a persisted {@code Transaction} with no domain field of its own.
     *
     * @param candidates already scoped by amount and date window (see {@link #DATE_WINDOW_DAYS})
     *                   and already excluding other {@code GMAIL_IMPORT} rows -- this method does
     *                   no filtering of its own beyond the merchant-similarity check
     */
    public Optional<Transaction> findMatchAmongTransactions(Transaction gmailTransaction, List<Transaction> candidates) {
        String gmailReduced = CategoryRules.extractMerchant(gmailTransaction.getDescription());
        if (gmailReduced.isBlank()) return Optional.empty();

        return candidates.stream()
                .filter(t -> reducedTokenSimilarity(gmailReduced, t.getDescription()) >= SIMILARITY_THRESHOLD)
                .max(Comparator.<Transaction>comparingDouble(t -> reducedTokenSimilarity(gmailReduced, t.getDescription()))
                        .thenComparing(t -> -Math.abs(t.getTxnDate().toEpochDay() - gmailTransaction.getTxnDate().toEpochDay())));
    }

    /** Best similarity across every (token of {@code reducedA}, token of {@code extractMerchant(rawB)})
     *  pair -- symmetric, unlike {@link #bestTokenSimilarity}, which fixes one side to a single
     *  pre-derived brand token. */
    private static double reducedTokenSimilarity(String reducedA, String rawB) {
        String reducedB = CategoryRules.extractMerchant(rawB);
        double best = 0.0;
        for (String tokenA : reducedA.split(" ")) {
            if (tokenA.isBlank() || tokenA.length() < MIN_BRAND_TOKEN_LENGTH) continue;
            for (String tokenB : reducedB.split(" ")) {
                if (tokenB.isBlank()) continue;
                best = Math.max(best, similarity(tokenA, tokenB));
            }
        }
        return best;
    }

    /** {@code "amazon.in"} -> {@code "amazon"}. Domains here are always bare registrable names
     *  ({@code merchant_templates.merchant_domain}'s own seeded rows: {@code "zomato.com"},
     *  never {@code "www.zomato.com"}), so the token before the first dot is the brand. */
    private static String brandTokenOf(String merchantDomain) {
        if (merchantDomain == null) return "";
        int dot = merchantDomain.indexOf('.');
        return (dot < 0 ? merchantDomain : merchantDomain.substring(0, dot)).toLowerCase();
    }

    /** The best similarity between the brand token and any significant token of a bank
     *  description, reduced through {@link CategoryRules#extractMerchant} first so a reference
     *  number embedded in the description (already stripped by that reduction) can't itself be
     *  compared as if it were a merchant name. */
    private static double bestTokenSimilarity(String brandToken, String description) {
        String reduced = CategoryRules.extractMerchant(description);
        double best = 0.0;
        for (String token : reduced.split(" ")) {
            if (token.isBlank()) continue;
            best = Math.max(best, similarity(brandToken, token));
        }
        return best;
    }

    private static double similarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - (double) levenshteinDistance(a, b) / maxLen;
    }

    private static int levenshteinDistance(String a, String b) {
        int[] previousRow = new int[b.length() + 1];
        int[] currentRow = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previousRow[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            currentRow[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitutionCost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                currentRow[j] = Math.min(
                        Math.min(currentRow[j - 1] + 1, previousRow[j] + 1),
                        previousRow[j - 1] + substitutionCost);
            }
            int[] swap = previousRow;
            previousRow = currentRow;
            currentRow = swap;
        }
        return previousRow[b.length()];
    }
}
