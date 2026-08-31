package com.finora.imports;

import com.finora.entity.Account;
import com.finora.repository.AccountRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An {@link AccountRepository} reporting exactly one live account for any user, for the many
 * staging tests that construct a {@link DuplicateDetector} directly and never cared about the
 * live-account-scoping fix itself.
 *
 * <p>Exists because {@code DuplicateDetector} now scopes both its query paths to the user's live
 * accounts (excluding a soft-deleted account's transactions -- see {@code DashboardService}'s own
 * doc comment on why an unscoped query keeps returning a deleted account's rows forever), which
 * added the dependency to every constructor call. Those tests previously got an implicit "the
 * duplicate check ran against everything" behaviour; this preserves that -- one live account is
 * always in scope -- without 40+ copies of the same three-line stub.
 */
public final class TestAccountRepositories {

    private TestAccountRepositories() {}

    /** One live account, returned for whatever userId is asked about. */
    public static AccountRepository anyLive() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        Account liveAccount = new Account();
        ReflectionTestUtils.setField(liveAccount, "id", UUID.randomUUID());
        when(accountRepository.findByUserId(any())).thenReturn(List.of(liveAccount));
        return accountRepository;
    }
}
