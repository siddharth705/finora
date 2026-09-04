package com.finora.service;

import com.finora.dto.AdminDtos.ReconciliationStatsDto;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Platform-wide Reconciliation Monitor stats -- mocked-repository unit test. Proves the grouped
 *  (status, count) rows get mapped to the right named field regardless of what order they come
 *  back in, and that a status with zero rows (never returned by a GROUP BY query) still reads as
 *  0 rather than throwing. */
class AdminReconciliationStatsServiceTest {

    private TransactionRepository transactionRepository;
    private AdminReconciliationStatsService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        service = new AdminReconciliationStatsService(transactionRepository);
    }

    @Test
    void platformStats_mapsEachStatusRowToItsOwnNamedField() {
        when(transactionRepository.platformReconciliationStatusCounts()).thenReturn(List.of(
                new Object[]{Transaction.ReconciliationStatus.OK, 100L},
                new Object[]{Transaction.ReconciliationStatus.DUPLICATE, 3L},
                new Object[]{Transaction.ReconciliationStatus.TRANSFER, 8L},
                new Object[]{Transaction.ReconciliationStatus.REFUND, 2L},
                new Object[]{Transaction.ReconciliationStatus.REVERSAL, 4L},
                new Object[]{Transaction.ReconciliationStatus.INVESTMENT_TRANSFER, 6L},
                new Object[]{Transaction.ReconciliationStatus.SUPERSEDED, 1L}
        ));
        when(transactionRepository.countPlatformRecurring()).thenReturn(15L);

        ReconciliationStatsDto stats = service.platformStats();

        assertThat(stats.okCount()).isEqualTo(100L);
        assertThat(stats.duplicateCount()).isEqualTo(3L);
        assertThat(stats.transferCount()).isEqualTo(8L);
        assertThat(stats.refundCount()).isEqualTo(2L);
        assertThat(stats.reversalCount()).isEqualTo(4L);
        assertThat(stats.investmentTransferCount()).isEqualTo(6L);
        assertThat(stats.supersededCount()).isEqualTo(1L);
        assertThat(stats.recurringCount()).isEqualTo(15L);
        // 100+3+8+2+4+6+1 -- CodeQL (java/missing-case-in-switch) caught this: this used to be
        // just okCount+duplicateCount+transferCount+refundCount (113), silently dropping
        // REVERSAL/INVESTMENT_TRANSFER/SUPERSEDED rows from the platform total entirely, with no
        // error. Asserted against the full 124 specifically so a regression back to the partial
        // sum fails this test, not just a future one.
        assertThat(stats.totalTransactions()).isEqualTo(124L);
    }

    @Test
    void platformStats_treatsAMissingStatusRowAsZero_notAnError() {
        // A brand-new platform with zero DUPLICATE/TRANSFER/REFUND rows would never get those
        // three back from a GROUP BY query at all -- only OK.
        // Note: List.of(new Object[]{...}) with a SINGLE array argument is genuinely ambiguous --
        // javac resolves it as the varargs form List.of(E... elements) with E=Object (spreading
        // the array's own contents as the list, giving List<Object> of size 2 here: [OK, 5L]),
        // not as a one-element List<Object[]>, even though the mocked method's real return type is
        // List<Object[]>. This is a real compile failure, not a style nit -- Collections.singletonList
        // has no varargs sibling to conflict with, so there's no ambiguity to resolve.
        when(transactionRepository.platformReconciliationStatusCounts()).thenReturn(Collections.singletonList(
                new Object[]{Transaction.ReconciliationStatus.OK, 5L}
        ));
        when(transactionRepository.countPlatformRecurring()).thenReturn(0L);

        ReconciliationStatsDto stats = service.platformStats();

        assertThat(stats.okCount()).isEqualTo(5L);
        assertThat(stats.duplicateCount()).isZero();
        assertThat(stats.transferCount()).isZero();
        assertThat(stats.refundCount()).isZero();
        assertThat(stats.totalTransactions()).isEqualTo(5L);
    }
}
