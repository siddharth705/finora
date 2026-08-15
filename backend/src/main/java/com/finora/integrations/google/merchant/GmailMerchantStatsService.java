package com.finora.integrations.google.merchant;

import com.finora.dto.AdminDtos.GmailMerchantParserStatDto;
import com.finora.integrations.google.GmailProcessedMessage.Outcome;
import com.finora.integrations.google.GmailProcessedMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-merchant-domain Gmail parser health for the admin Merchant Intelligence page — C6.2. The
 * problem this answers: a merchant changes their email template, a parser's success rate silently
 * collapses, and today Finora finds out when a user complains rather than before.
 *
 * <p>Deliberately just one query aggregated in Java, the same "cheap live aggregate, not a new
 * reporting subsystem" shape {@code AdminMerchantStatsService} and
 * {@code StatementAnalysisReportService#failureCounts} already use — no new table. A candidate
 * design (a persisted {@code merchant_extraction_metrics} table, updated on every message) was
 * considered and rejected: {@code gmail_processed_messages} already holds one row per message
 * forever (C4's own idempotency requirement), so a second table would only duplicate it, adding a
 * write path that could drift from the source of truth for a query {@code GROUP BY} already
 * answers directly.
 */
@Service
public class GmailMerchantStatsService {

    private static final Outcome[] TRACKED_OUTCOMES = {
            Outcome.PARSED, Outcome.PARSE_FAILED, Outcome.SKIPPED_NOT_RECEIPT, Outcome.DETECTED_NOT_STAGED
    };

    private final GmailProcessedMessageRepository processedMessages;

    public GmailMerchantStatsService(GmailProcessedMessageRepository processedMessages) {
        this.processedMessages = processedMessages;
    }

    /**
     * @param since no default, matching {@link GmailProcessedMessageRepository#merchantOutcomeCounts}'s
     *              own reasoning — an unbounded scan is a cost this method should never silently
     *              absorb on a caller's behalf.
     */
    @Transactional(readOnly = true)
    public List<GmailMerchantParserStatDto> parserStats(Instant since) {
        Map<String, long[]> countsByDomain = new LinkedHashMap<>();
        Map<String, Instant> lastSeenByDomain = new LinkedHashMap<>();

        for (Object[] row : processedMessages.merchantOutcomeCounts(since)) {
            String domain = (String) row[0];
            Outcome outcome = (Outcome) row[1];
            long count = (long) row[2];
            Instant lastSeen = (Instant) row[3];

            long[] counts = countsByDomain.computeIfAbsent(domain, d -> new long[TRACKED_OUTCOMES.length]);
            counts[indexOf(outcome)] += count;
            lastSeenByDomain.merge(domain, lastSeen, (a, b) -> a.isAfter(b) ? a : b);
        }

        return countsByDomain.entrySet().stream()
                .map(entry -> toDto(entry.getKey(), entry.getValue(), lastSeenByDomain.get(entry.getKey())))
                .sorted(WORST_SUCCESS_RATE_FIRST)
                .toList();
    }

    private static GmailMerchantParserStatDto toDto(String domain, long[] counts, Instant lastSeen) {
        long parsed = counts[indexOf(Outcome.PARSED)];
        long parseFailed = counts[indexOf(Outcome.PARSE_FAILED)];
        long skippedNotReceipt = counts[indexOf(Outcome.SKIPPED_NOT_RECEIPT)];
        long noParserYet = counts[indexOf(Outcome.DETECTED_NOT_STAGED)];

        // The denominator is every outcome an EXISTING parser can produce for this domain. Deliberately
        // excludes noParserYet: that outcome means no parser has ever run for this domain at all, and
        // folding it in would make every uncovered domain read as "0% success" -- indistinguishable from
        // a parser that used to work and broke, which is the actual signal this page exists to surface.
        long covered = parsed + parseFailed + skippedNotReceipt;
        Double successRate = covered == 0 ? null : (double) parsed / covered;

        return new GmailMerchantParserStatDto(domain, GmailReviewService.displayNameFor(domain),
                parsed, parseFailed, skippedNotReceipt, noParserYet, successRate, lastSeen);
    }

    private static int indexOf(Outcome outcome) {
        for (int i = 0; i < TRACKED_OUTCOMES.length; i++) {
            if (TRACKED_OUTCOMES[i] == outcome) return i;
        }
        throw new IllegalStateException("Untracked outcome reached the merchant stats aggregation: " + outcome);
    }

    /**
     * Worst success rate first -- the domains most worth an admin's attention right now. A domain
     * with no parser coverage yet ({@code successRate == null}) sorts after every domain with a
     * real, measurable rate: a missing parser was never claimed to work, so it is a lower-urgency
     * signal than a parser that WAS working and has since regressed. Domain name breaks ties, for
     * the same determinism reason {@code failureCodeLayoutCounts}' caller sorts on a secondary key
     * rather than trusting aggregation order.
     */
    private static final Comparator<GmailMerchantParserStatDto> WORST_SUCCESS_RATE_FIRST =
            Comparator.comparing(GmailMerchantParserStatDto::successRate,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(GmailMerchantParserStatDto::domain);
}
