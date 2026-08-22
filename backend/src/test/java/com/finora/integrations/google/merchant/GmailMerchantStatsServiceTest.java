package com.finora.integrations.google.merchant;

import com.finora.dto.AdminDtos.GmailMerchantParserStatDto;
import com.finora.integrations.google.GmailProcessedMessage.Outcome;
import com.finora.integrations.google.GmailProcessedMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C6.2. Every collaborator is mocked -- {@code GmailProcessedMessageIT} proves the real {@code
 * GROUP BY} query executes against Postgres; this proves the aggregation math (success rate,
 * the no-parser-coverage null, tie-break ordering) is correct given rows shaped the way that
 * query actually returns them.
 */
class GmailMerchantStatsServiceTest {

    private GmailProcessedMessageRepository processedMessages;
    private GmailMerchantStatsService service;
    private final Instant since = Instant.parse("2026-08-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        processedMessages = mock(GmailProcessedMessageRepository.class);
        service = new GmailMerchantStatsService(processedMessages);
    }

    private Object[] row(String domain, Outcome outcome, long count, Instant lastSeen) {
        return new Object[]{domain, outcome, count, lastSeen};
    }

    @Test
    @DisplayName("success rate excludes no-parser-coverage volume from its denominator")
    void successRateIsParsedOverCoveredOutcomesOnly() {
        Instant lastSeen = since.plus(1, ChronoUnit.DAYS);
        when(processedMessages.merchantOutcomeCounts(since)).thenReturn(List.of(
                row("amazon.in", Outcome.PARSED, 8, lastSeen),
                row("amazon.in", Outcome.PARSE_FAILED, 2, since),
                // DETECTED_NOT_STAGED is coverage-gap volume, not a parser attempt -- must not
                // dilute the rate the way including it in the denominator would (8 / 20 = 40%
                // instead of the correct 8 / 10 = 80%).
                row("amazon.in", Outcome.DETECTED_NOT_STAGED, 10, since)
        ));

        List<GmailMerchantParserStatDto> stats = service.parserStats(since);

        assertThat(stats).hasSize(1);
        GmailMerchantParserStatDto amazon = stats.get(0);
        assertThat(amazon.domain()).isEqualTo("amazon.in");
        assertThat(amazon.merchant()).isEqualTo("Amazon");
        assertThat(amazon.parsed()).isEqualTo(8);
        assertThat(amazon.parseFailed()).isEqualTo(2);
        assertThat(amazon.noParserYet()).isEqualTo(10);
        assertThat(amazon.successRate()).isEqualTo(0.8);
        assertThat(amazon.lastSeen()).isEqualTo(lastSeen);
    }

    @Test
    @DisplayName("a domain with only no-parser-coverage traffic has a null rate, not zero")
    void noParserCoverageYieldsNullRateNotZero() {
        when(processedMessages.merchantOutcomeCounts(since)).thenReturn(List.<Object[]>of(
                row("newmerchant.example", Outcome.DETECTED_NOT_STAGED, 5, since)
        ));

        List<GmailMerchantParserStatDto> stats = service.parserStats(since);

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0).successRate()).isNull();
        assertThat(stats.get(0).noParserYet()).isEqualTo(5);
        assertThat(stats.get(0).parsed()).isZero();
    }

    @Test
    @DisplayName("skipped-not-receipt counts toward coverage but never toward success")
    void skippedNotReceiptLowersRateWithoutBeingAFailure() {
        when(processedMessages.merchantOutcomeCounts(since)).thenReturn(List.of(
                row("zomato.com", Outcome.PARSED, 3, since),
                row("zomato.com", Outcome.SKIPPED_NOT_RECEIPT, 7, since)
        ));

        GmailMerchantParserStatDto zomato = service.parserStats(since).get(0);

        assertThat(zomato.skippedNotReceipt()).isEqualTo(7);
        assertThat(zomato.successRate()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("worst success rate sorts first, no-coverage domains sort after every measurable rate")
    void ordersWorstRateFirstAndPushesNoCoverageLast() {
        when(processedMessages.merchantOutcomeCounts(since)).thenReturn(List.of(
                row("healthy.example", Outcome.PARSED, 10, since),
                row("broken.example", Outcome.PARSED, 1, since),
                row("broken.example", Outcome.PARSE_FAILED, 9, since),
                row("uncovered.example", Outcome.DETECTED_NOT_STAGED, 4, since)
        ));

        List<GmailMerchantParserStatDto> stats = service.parserStats(since);

        assertThat(stats).extracting(GmailMerchantParserStatDto::domain)
                .containsExactly("broken.example", "healthy.example", "uncovered.example");
    }

    @Test
    @DisplayName("equal rates break the tie by domain name, deterministically")
    void tiedRatesBreakByDomainAscending() {
        when(processedMessages.merchantOutcomeCounts(since)).thenReturn(List.of(
                row("zzz.example", Outcome.PARSED, 1, since),
                row("aaa.example", Outcome.PARSED, 1, since)
        ));

        assertThat(service.parserStats(since))
                .extracting(GmailMerchantParserStatDto::domain)
                .containsExactly("aaa.example", "zzz.example");
    }

    @Test
    @DisplayName("an unknown domain falls back to itself, same as the review queue's display name")
    void unknownDomainDisplayNameFallsBackToTheDomain() {
        when(processedMessages.merchantOutcomeCounts(since)).thenReturn(List.<Object[]>of(
                row("unknown-merchant.example", Outcome.PARSED, 1, since)
        ));

        assertThat(service.parserStats(since).get(0).merchant()).isEqualTo("unknown-merchant.example");
    }

    @Test
    @DisplayName("no rows in the window returns an empty list, not an error")
    void emptyWindowReturnsEmptyList() {
        when(processedMessages.merchantOutcomeCounts(since)).thenReturn(List.of());

        assertThat(service.parserStats(since)).isEmpty();
    }
}
