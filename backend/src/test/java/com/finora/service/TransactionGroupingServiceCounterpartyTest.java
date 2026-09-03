package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The counterparty half of {@link TransactionGroupingService}, kept separate from {@link
 * TransactionGroupingServiceTest} the same way {@code CounterpartyWiringTest} is split from {@code
 * TransactionServiceTest} elsewhere in this codebase -- a distinct concern with its own full
 * coverage reads better as its own file than interleaved into an already-large one.
 */
class TransactionGroupingServiceCounterpartyTest {

    private final UUID userId = UUID.randomUUID();

    private Transaction txn(CounterpartyType type, String key, BigDecimal amount, String description) {
        Transaction t = new Transaction();
        org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setTxnDate(LocalDate.of(2026, 1, 1));
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(description);
        t.setCounterpartyType(type);
        t.setCounterpartyKey(key);
        return t;
    }

    private AccountRepository accountRepositoryWithOneLiveAccount() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        Account account = new Account();
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        account.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account));
        return accountRepository;
    }

    private TransactionGroupingService serviceWith(List<Transaction> candidates) {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(eq(userId), any()))
                .thenReturn(candidates);
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        return new TransactionGroupingService(transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
    }

    @Test
    void groupsByCounterpartyKey_excludingGroupsOfOne() {
        Transaction a = txn(CounterpartyType.PERSON, "vpa:sunilverma", BigDecimal.TEN, "UPI to Sunil");
        Transaction b = txn(CounterpartyType.PERSON, "vpa:sunilverma", BigDecimal.TEN, "UPI to Sunil");
        Transaction c = txn(CounterpartyType.PERSON, "vpa:onlyonce", BigDecimal.TEN, "UPI once");

        var groups = serviceWith(List.of(a, b, c)).groupNeedsReviewByCounterparty(userId);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).counterpartyKey()).isEqualTo("vpa:sunilverma");
        assertThat(groups.get(0).transactionIds()).hasSize(2);
    }

    @Test
    void excludesRowsThatAlreadyHaveAMerchantMatch() {
        // The partition with groupNeedsReviewByMerchant -- a merchant-matched row already has a
        // strong category signal and stays in that grouping, never doubled up here.
        Transaction hasMerchant = txn(CounterpartyType.PERSON, "vpa:sunilverma", BigDecimal.TEN, "x");
        hasMerchant.setMerchantId(UUID.randomUUID());
        Transaction alsoHasMerchant = txn(CounterpartyType.PERSON, "vpa:sunilverma", BigDecimal.TEN, "x");
        alsoHasMerchant.setMerchantId(UUID.randomUUID());

        var groups = serviceWith(List.of(hasMerchant, alsoHasMerchant)).groupNeedsReviewByCounterparty(userId);

        assertThat(groups).isEmpty();
    }

    @Test
    void excludesFinancialInstitutionAndGovernmentAndUnknownRows() {
        Transaction bank = txn(CounterpartyType.FINANCIAL_INSTITUTION, "name:hsbc mf", BigDecimal.TEN, "x");
        Transaction bank2 = txn(CounterpartyType.FINANCIAL_INSTITUTION, "name:hsbc mf", BigDecimal.TEN, "x");
        Transaction govt = txn(CounterpartyType.GOVERNMENT, "name:gst", BigDecimal.TEN, "x");
        Transaction govt2 = txn(CounterpartyType.GOVERNMENT, "name:gst", BigDecimal.TEN, "x");
        Transaction unknown = txn(CounterpartyType.UNKNOWN, "vpa:mystery", BigDecimal.TEN, "x");
        Transaction unknown2 = txn(CounterpartyType.UNKNOWN, "vpa:mystery", BigDecimal.TEN, "x");

        var groups = serviceWith(List.of(bank, bank2, govt, govt2, unknown, unknown2))
                .groupNeedsReviewByCounterparty(userId);

        assertThat(groups).isEmpty();
    }

    @Test
    void excludesRowsWithNoCounterpartyKey() {
        Transaction a = txn(CounterpartyType.PERSON, null, BigDecimal.TEN, "x");
        Transaction b = txn(CounterpartyType.PERSON, null, BigDecimal.TEN, "x");

        var groups = serviceWith(List.of(a, b)).groupNeedsReviewByCounterparty(userId);

        assertThat(groups).isEmpty();
    }

    @Test
    void excludesTransactionsAlreadyFlaggedAsDuplicate() {
        Transaction original = txn(CounterpartyType.PERSON, "vpa:x", BigDecimal.TEN, "x");
        Transaction duplicate = txn(CounterpartyType.PERSON, "vpa:x", BigDecimal.TEN, "x");
        duplicate.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        Transaction another = txn(CounterpartyType.PERSON, "vpa:x", BigDecimal.TEN, "x");

        var groups = serviceWith(List.of(original, duplicate, another)).groupNeedsReviewByCounterparty(userId);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).transactionIds()).hasSize(2);
    }

    @Test
    void sortsByTotalValue_notRowCount() {
        // The whole point of this grouping over the merchant one. Five small rows must NOT
        // outrank two large ones -- measured on the real corpus, unresolved VALUE concentrates far
        // more sharply than unresolved row count, and a count-sort misses that concentration.
        Transaction smallA = txn(CounterpartyType.PERSON, "vpa:frequent", BigDecimal.valueOf(50), "x");
        Transaction smallB = txn(CounterpartyType.PERSON, "vpa:frequent", BigDecimal.valueOf(50), "x");
        Transaction smallC = txn(CounterpartyType.PERSON, "vpa:frequent", BigDecimal.valueOf(50), "x");
        Transaction bigA = txn(CounterpartyType.PERSON, "vpa:rare", BigDecimal.valueOf(20000), "x");
        Transaction bigB = txn(CounterpartyType.PERSON, "vpa:rare", BigDecimal.valueOf(20000), "x");

        var groups = serviceWith(List.of(smallA, smallB, smallC, bigA, bigB))
                .groupNeedsReviewByCounterparty(userId);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).counterpartyKey()).isEqualTo("vpa:rare");
        assertThat(groups.get(0).totalValue()).isEqualByComparingTo("40000");
        assertThat(groups.get(1).counterpartyKey()).isEqualTo("vpa:frequent");
        assertThat(groups.get(1).totalValue()).isEqualByComparingTo("150");
    }

    @Test
    void totalValueSumsAbsoluteAmounts_soAnIncomeRowDoesNotCancelAnExpenseRow() {
        // A counterparty can appear on both sides -- money sent and received -- and both count
        // toward "how much value is tied up with this person", not toward a net that could hide it.
        Transaction sent = txn(CounterpartyType.PERSON, "vpa:x", BigDecimal.valueOf(-500), "x");
        Transaction received = txn(CounterpartyType.PERSON, "vpa:x", BigDecimal.valueOf(300), "x");

        var groups = serviceWith(List.of(sent, received)).groupNeedsReviewByCounterparty(userId);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).totalValue()).isEqualByComparingTo("800");
    }

    @Test
    void theLabelIsTheMostRecentTransactionsNarration_notAnInventedResolvedName() {
        // needsReviewCandidates is ORDER BY txnDate DESC, so the mock's list order IS the
        // most-recent-first order this method relies on -- the first element is "most recent".
        Transaction mostRecent = txn(CounterpartyType.PERSON, "vpa:x", BigDecimal.TEN, "UPI-SUNIL VERMA-REF99");
        Transaction older = txn(CounterpartyType.PERSON, "vpa:x", BigDecimal.TEN, "UPI/SUNILV/REF12");

        var groups = serviceWith(List.of(mostRecent, older)).groupNeedsReviewByCounterparty(userId);

        assertThat(groups.get(0).label()).isEqualTo("UPI-SUNIL VERMA-REF99");
    }

    @Test
    void identityIsStrongReflectsAVpaKey_falseForANameKey() {
        Transaction strong1 = txn(CounterpartyType.PERSON, "vpa:x", BigDecimal.TEN, "x");
        Transaction strong2 = txn(CounterpartyType.PERSON, "vpa:x", BigDecimal.TEN, "x");
        Transaction weak1 = txn(CounterpartyType.BUSINESS, "name:acme corp", BigDecimal.TEN, "x");
        Transaction weak2 = txn(CounterpartyType.BUSINESS, "name:acme corp", BigDecimal.TEN, "x");

        var groups = serviceWith(List.of(strong1, strong2, weak1, weak2)).groupNeedsReviewByCounterparty(userId);

        var strongGroup = groups.stream().filter(g -> g.counterpartyKey().equals("vpa:x")).findFirst().orElseThrow();
        var weakGroup = groups.stream().filter(g -> g.counterpartyKey().equals("name:acme corp")).findFirst().orElseThrow();
        assertThat(strongGroup.identityIsStrong()).isTrue();
        assertThat(weakGroup.identityIsStrong()).isFalse();
    }

    @Test
    void aRareMixedTypeGroupTakesTheMostRecentTransactionsType_ratherThanFailing() {
        // Measured on the real corpus: 2 of 621 distinct PERSON/BUSINESS keys (0.3%) carry BOTH
        // types across their own occurrences. The grouping itself (by counterpartyKey) is
        // unaffected either way -- this pins that the accepted tradeoff (most-recent-wins for the
        // displayed badge) is what actually happens, not an unspecified/crashing case.
        Transaction older = txn(CounterpartyType.BUSINESS, "vpa:eatclub", BigDecimal.TEN, "older");
        Transaction mostRecent = txn(CounterpartyType.PERSON, "vpa:eatclub", BigDecimal.TEN, "newest");
        // needsReviewCandidates is ORDER BY txnDate DESC -- most recent first in the mocked list.

        var group = serviceWith(List.of(mostRecent, older)).groupNeedsReviewByCounterparty(userId).get(0);

        assertThat(group.counterpartyType()).isEqualTo(CounterpartyType.PERSON);
        assertThat(group.transactionIds()).hasSize(2); // grouping itself is unaffected
    }

    @Test
    void groupTransactionsCarryTheSameIdsAndOrderAsTransactionIds() {
        Transaction first = txn(CounterpartyType.PERSON, "vpa:x", BigDecimal.valueOf(250), "ONE");
        Transaction second = txn(CounterpartyType.PERSON, "vpa:x", BigDecimal.valueOf(480), "TWO");

        var group = serviceWith(List.of(first, second)).groupNeedsReviewByCounterparty(userId).get(0);

        assertThat(group.transactions()).extracting(TransactionGroupingService.TransactionSummary::id)
                .containsExactlyElementsOf(group.transactionIds());
        assertThat(group.transactions()).extracting(TransactionGroupingService.TransactionSummary::description)
                .containsExactly("ONE", "TWO");
    }

    @Test
    void withNoLiveAccounts_shortCircuits_withoutQueryingTransactions() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        MerchantRepository merchantRepository = mock(MerchantRepository.class);

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository, accountRepository);
        List<TransactionGroupingService.CounterpartyGroup> groups = service.groupNeedsReviewByCounterparty(userId);

        assertThat(groups).isEmpty();
        org.mockito.Mockito.verify(transactionRepository, org.mockito.Mockito.never())
                .findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(any(), any());
    }
}
