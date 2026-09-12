package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AccountAggregatorLinkSweepServiceTest {

    @Test
    void reapsLinksStuckPastTheTtlIntoLinkFailed() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLinkSweepService sweep = new AccountAggregatorLinkSweepService(links, 48);

        AccountAggregatorLink stale = new AccountAggregatorLink();
        stale.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        when(links.findByStatusInAndCreatedAtBefore(
                eq(List.of(AccountAggregatorLinkStatus.CONSENT_PENDING,
                        AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION)),
                any(Instant.class)))
                .thenReturn(List.of(stale));

        int reaped = sweep.sweepStaleLinks();

        assertThat(reaped).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo(AccountAggregatorLinkStatus.LINK_FAILED);
        verify(links).save(stale);
    }

    @Test
    void doesNothingWhenNoLinksAreStale() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        when(links.findByStatusInAndCreatedAtBefore(any(), any())).thenReturn(List.of());
        AccountAggregatorLinkSweepService sweep = new AccountAggregatorLinkSweepService(links, 48);

        assertThat(sweep.sweepStaleLinks()).isZero();
    }
}
