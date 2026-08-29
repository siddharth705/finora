package com.finora.integrations.google.merchant;

import com.finora.dto.ImportDto.DuplicateMatch;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C6.4, staging-time direction. The candidate SET (same amount, date window, not Gmail-sourced)
 * is the repository's job — see {@code TransactionRepositoryIT} for that half. This exercises the
 * scoring this class owns: which candidate, if any, actually looks like the same business.
 */
class GmailReconciliationMatcherTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private GmailReconciliationMatcher matcher;

    private final UUID userId = UUID.randomUUID();
    private Account liveAccount;
    // Backs accountRepository.findByUserId -- see liveAccounts' own comment in
    // ReconciliationServiceTest, the pattern this mirrors.
    private List<Account> liveAccounts;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        liveAccount = new Account();
        ReflectionTestUtils.setField(liveAccount, "id", UUID.randomUUID());
        liveAccount.setUserId(userId);
        liveAccounts = new ArrayList<>(List.of(liveAccount));
        when(accountRepository.findByUserId(userId)).thenAnswer(inv -> new ArrayList<>(liveAccounts));
        matcher = new GmailReconciliationMatcher(transactionRepository, accountRepository);
    }

    @Test
    @DisplayName("an abbreviated bank description of the same brand is a match -- the exact case the design doc names")
    void abbreviatedBrandNameMatches() {
        Transaction candidate = transaction("AMZN MKTPLACE 4521", LocalDate.of(2026, 8, 9));
        when(transactionRepository.findCandidatesForGmailReconciliationAndAccountIdIn(any(), any(), any(), any(), any()))
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
        when(transactionRepository.findCandidatesForGmailReconciliationAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of(candidate));

        Optional<DuplicateMatch> match = matcher.findMatch(userId, LocalDate.of(2026, 8, 10),
                new BigDecimal("1299.00"), "amazon.in");

        assertThat(match).isEmpty();
    }

    @Test
    @DisplayName("no candidates at all is not a match, not an error")
    void noCandidatesIsNotAMatch() {
        when(transactionRepository.findCandidatesForGmailReconciliationAndAccountIdIn(any(), any(), any(), any(), any()))
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
        when(transactionRepository.findCandidatesForGmailReconciliationAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of(weakMatch, strongMatch));

        Optional<DuplicateMatch> match = matcher.findMatch(userId, LocalDate.of(2026, 8, 10),
                new BigDecimal("1299.00"), "amazon.in");

        assertThat(match).isPresent();
        assertThat(match.get().existingTransactionId()).isEqualTo(strongMatch.getId());
    }

    @Test
    @DisplayName("the query is asked with a date window around the receipt date, not the receipt date alone")
    void queriesADateWindowAroundTheReceiptDate() {
        when(transactionRepository.findCandidatesForGmailReconciliationAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        matcher.findMatch(userId, LocalDate.of(2026, 8, 10), new BigDecimal("1299.00"), "amazon.in");

        org.mockito.Mockito.verify(transactionRepository).findCandidatesForGmailReconciliationAndAccountIdIn(
                userId, new BigDecimal("1299.00"), LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 13),
                List.of(liveAccount.getId()));
    }

    // --- Deleted-account leak (see DashboardService.summarize for the original fix, and
    // ReconciliationServiceTest's CC_PAYMENT-pass tests for the same pattern applied there):
    // findMatch is deliberately cross-account by design -- a Gmail receipt isn't tied to an
    // account until it's matched -- but must stop matching against a deleted account's history,
    // not keep doing so forever the way the plain user-scoped query would.

    @Test
    @DisplayName("scopes the candidate query to exactly the user's live account ids")
    void scopesQueryToExactlyTheLiveAccountIds() {
        when(transactionRepository.findCandidatesForGmailReconciliationAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        matcher.findMatch(userId, LocalDate.of(2026, 8, 10), new BigDecimal("1299.00"), "amazon.in");

        org.mockito.Mockito.verify(transactionRepository).findCandidatesForGmailReconciliationAndAccountIdIn(
                eq(userId), eq(new BigDecimal("1299.00")), any(), any(), eq(List.of(liveAccount.getId())));
    }

    @Test
    @DisplayName("a transaction whose account was deleted no longer counts as a candidate once it's removed from the live set")
    void deletedAccountNoLongerContributesCandidates() {
        // Registers a match by stubbing the scoped query to answer as if the database itself
        // excluded a since-deleted account's rows -- this asserts the matcher asks the query with
        // exactly the shrunken live-account list, mirroring ReconciliationServiceTest's
        // liveAccounts.removeIf(...) pattern for the CC_PAYMENT-pass fix.
        liveAccounts.removeIf(a -> a.getId().equals(liveAccount.getId()));
        when(accountRepository.findByUserId(userId)).thenAnswer(inv -> new ArrayList<>(liveAccounts));

        Optional<DuplicateMatch> match = matcher.findMatch(userId, LocalDate.of(2026, 8, 10),
                new BigDecimal("1299.00"), "amazon.in");

        assertThat(match).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(transactionRepository);
    }

    // --- findMatchAmongTransactions: ReconciliationService's post-confirm sibling of findMatch ---

    @Test
    @DisplayName("a counterparty-name Gmail description matches an abbreviated bank description of the same brand")
    void counterpartyNameMatchesAnAbbreviatedBankDescription() {
        // Unlike findMatch's domain string ("amazon.in"), a confirmed Gmail transaction's own
        // description is whatever descriptionFor(receipt) chose -- here a plain counterparty name,
        // which brandTokenOf's dot-splitting would mishandle. findMatchAmongTransactions must not
        // depend on that assumption.
        Transaction gmailTxn = transaction("Amazon", LocalDate.of(2026, 8, 10));
        gmailTxn.setSource(Transaction.Source.GMAIL_IMPORT);
        Transaction bankTxn = transaction("AMZN MKTPLACE 4521", LocalDate.of(2026, 8, 9));

        Optional<Transaction> match = matcher.findMatchAmongTransactions(gmailTxn, List.of(bankTxn));

        assertThat(match).contains(bankTxn);
    }

    @Test
    @DisplayName("an unrelated candidate is not a match")
    void findMatchAmongTransactions_unrelatedCandidateDoesNotMatch() {
        Transaction gmailTxn = transaction("Amazon", LocalDate.of(2026, 8, 10));
        gmailTxn.setSource(Transaction.Source.GMAIL_IMPORT);
        Transaction bankTxn = transaction("SWIGGY ORDER 9182", LocalDate.of(2026, 8, 10));

        assertThat(matcher.findMatchAmongTransactions(gmailTxn, List.of(bankTxn))).isEmpty();
    }

    @Test
    @DisplayName("no candidates at all is not a match")
    void findMatchAmongTransactions_noCandidatesIsNotAMatch() {
        Transaction gmailTxn = transaction("Amazon", LocalDate.of(2026, 8, 10));
        gmailTxn.setSource(Transaction.Source.GMAIL_IMPORT);

        assertThat(matcher.findMatchAmongTransactions(gmailTxn, List.of())).isEmpty();
    }

    @Test
    @DisplayName("among several candidates that both clear the threshold, the closer merchant-name match wins")
    void findMatchAmongTransactions_bestSimilarityWinsAmongSeveralCandidates() {
        Transaction gmailTxn = transaction("Amazon", LocalDate.of(2026, 8, 10));
        gmailTxn.setSource(Transaction.Source.GMAIL_IMPORT);
        Transaction weakMatch = transaction("AMZN REFUND", LocalDate.of(2026, 8, 8));
        Transaction strongMatch = transaction("AMAZON.IN PURCHASE", LocalDate.of(2026, 8, 9));

        Optional<Transaction> match = matcher.findMatchAmongTransactions(gmailTxn, List.of(weakMatch, strongMatch));

        assertThat(match).contains(strongMatch);
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
