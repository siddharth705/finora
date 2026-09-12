package com.finora.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountPrimarySourceTest {

    @Test
    void defaultsToManual() {
        Account account = new Account();
        assertThat(account.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
    }

    @Test
    void canBeSetToAccountAggregator() {
        Account account = new Account();
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        assertThat(account.getPrimarySource()).isEqualTo(Account.PrimarySource.ACCOUNT_AGGREGATOR);
    }
}
