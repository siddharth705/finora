package com.finora.integrations.google.merchant;

import com.finora.dto.ImportDto.DuplicateMatch;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C6.4, staging-time direction. The candidate SET (same amount, date window, not Gmail-sourced)
 * is the repository's job — see {@code TransactionRepositoryIT} for that half. This exercises the
 * scoring this class owns: which candidate, if any, actually looks like the same business.
 */
class GmailReconciliationMatcherTest {

    private TransactionRepository transactionRepository;
    private GmailReconciliationMatcher matcher;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        matcher = new GmailReconciliationMatcher(transactionRepository);
    }

    @Test
    @DisplayName("an abbreviated bank description of the same brand is a match -- the exact case the design doc names")
    void abbreviatedBrandNameMatches() {
        Transaction candidate = transaction("AMZN MKTPLACE 4521", LocalDate.of(2026, 8, 9));
        when(transactionRepository.findCandidatesForGmailReconciliation(any(), any(), any(), any()))
                .thenReturn(List.of(candidate));

        Optional<DuplicateMatch> match = matcher.findMatch(userId, LocalDate.of(2026, 8, 10),
                new BigDecimal("1299.00"), "amazon.in");

        assertThat(match).isPresent();
        assertThat(match.get().existingTransactionId()).isEqualTo(candidate.getId());
        assertThat(match.get().confidence()).isEqualTo("LIKELY");
    }

    @Test
    @DisplayName("an unrelated merchant at the same amount and date is not a match")
    void unrelatedMerchantDoesNotMatch() {
        Transaction candidate = transaction("SWIGGY ORDER 9182", LocalDate.of(2026, 8, 10));
        when(transactionRepository.findCandidatesForGmailReconciliation(any(), any(), any(), any()))
                .thenReturn(List.of(candidate));

        Optional<DuplicateMatch> match = matcher.findMatch(userId, LocalDate.of(2026, 8, 10),
                new BigDecimal("1299.00"), "amazon.in");

        assertThat(match).isEmpty();
    }

    @Test
    @DisplayName("no candidates at all is not a match, not an error")
    void noCandidatesIsNotAMatch() {
        when(transactionRepository.findCandidatesForGmailReconciliation(any(), any(), any(), any()))
                .thenReturn(List.of());

        Optional<DuplicateMatch> match = matcher.findMatch(userId, LocalDate.of(2026, 8, 10),
                new BigDecimal("1299.00"), "amazon.in");

        assertThat(match).isEmpty();
    }

    @Test
    @DisplayName("a very short domain brand token is never fuzzy-matched -- too short for edit distance to mean anything")
    void veryShortBrandTokenNeverMatches() {
        matcher.findMatch(userId, LocalDate.of(2026, 8, 10), new BigDecimal("500.00"), "hp.com");

        org.mockito.Mockito.verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("among several candidates that both clear the threshold, the closer merchant-name match wins")
    void bestSimilarityWinsAmongSeveralCandidates() {
        // "amzn" vs "amazon": 2 edits / 6 chars ~= 0.67 similarity -- clears the 0.6 threshold on
        // its own, so this candidate genuinely qualifies rather than being filtered out; the test
        // is only meaningful if BOTH candidates pass and the better one is still chosen.
        Transaction weakMatch = transaction("AMZN REFUND", LocalDate.of(2026, 8, 8));
        Transaction strongMatch = transaction("AMAZON.IN PURCHASE", LocalDate.of(2026, 8, 9));
        when(transactionRepository.findCandidatesForGmailReconciliation(any(), any(), any(), any()))
                .thenReturn(List.of(weakMatch, strongMatch));

        Optional<DuplicateMatch> match = matcher.findMatch(userId, LocalDate.of(2026, 8, 10),
                new BigDecimal("1299.00"), "amazon.in");

        assertThat(match).isPresent();
        assertThat(match.get().existingTransactionId()).isEqualTo(strongMatch.getId());
    }

    @Test
    @DisplayName("the query is asked with a date window around the receipt date, not the receipt date alone")
    void queriesADateWindowAroundTheReceiptDate() {
        when(transactionRepository.findCandidatesForGmailReconciliation(any(), any(), any(), any()))
                .thenReturn(List.of());

        matcher.findMatch(userId, LocalDate.of(2026, 8, 10), new BigDecimal("1299.00"), "amazon.in");

        org.mockito.Mockito.verify(transactionRepository).findCandidatesForGmailReconciliation(
                userId, new BigDecimal("1299.00"), LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 13));
    }

    private Transaction transaction(String description, LocalDate date) {
        Transaction t = new Transaction();
        org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setAccountId(UUID.randomUUID());
        t.setDescription(description);
        t.setTxnDate(date);
        t.setAmount(new BigDecimal("1299.00"));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setSource(Transaction.Source.CSV_IMPORT);
        return t;
    }
}
