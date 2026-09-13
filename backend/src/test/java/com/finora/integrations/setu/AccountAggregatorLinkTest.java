package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountAggregatorLinkTest {

    @Test
    void defaultsToConsentPendingWithNoAccountAttached() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        link.setFiType(FiType.DEPOSIT);
        link.setLinkIdempotencyKey("idem-key-1");

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.CONSENT_PENDING);
        assertThat(link.getAccountId()).isNull();
    }

    @Test
    void statusAndAccountCanBeSetIndependently() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        UUID accountId = UUID.randomUUID();

        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setAccountId(accountId);
        link.setConsentExpiresAt(Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        assertThat(link.getAccountId()).isEqualTo(accountId);
        assertThat(link.getConsentExpiresAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }
}
