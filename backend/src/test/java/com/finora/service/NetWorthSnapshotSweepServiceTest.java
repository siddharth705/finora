package com.finora.service;

import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Track C/C8's sweep control flow -- what gets saved, skipped, or left to retry on the next pass.
 * {@code NetWorthService.saveSnapshotForToday} itself (the per-user timezone resolution, the
 * upsert) is already covered by its own tests; this proves only that the sweep discovers the
 * right candidates and that one user's failure never takes the batch down with it.
 */
class NetWorthSnapshotSweepServiceTest {

    private AccountRepository accountRepository;
    private UserRepository userRepository;
    private NetWorthService netWorthService;
    private NetWorthSnapshotSweepService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        userRepository = mock(UserRepository.class);
        netWorthService = mock(NetWorthService.class);
        service = new NetWorthSnapshotSweepService(accountRepository, userRepository, netWorthService);
        ReflectionTestUtils.setField(service, "sweepEnabled", true);
    }

    @Test
    void everyActiveUserWithAnAccountGetsSnapshotted() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(user1, user2));
        when(userRepository.findById(user1)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findById(user2)).thenReturn(Optional.of(activeUser()));

        var result = service.sweep();

        assertThat(result.saved()).isEqualTo(2);
        assertThat(result.skipped()).isZero();
        assertThat(result.failed()).isZero();
        verify(netWorthService).saveSnapshotForToday(user1);
        verify(netWorthService).saveSnapshotForToday(user2);
    }

    @Test
    void aUserWithNoAccountIsNeverConsidered() {
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of());

        var result = service.sweep();

        assertThat(result.saved()).isZero();
        verify(userRepository, never()).findById(any());
        verify(netWorthService, never()).saveSnapshotForToday(any());
    }

    @Test
    void aNonActiveUserIsSkippedNotSnapshotted() {
        UUID suspended = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(suspended));
        User u = new User();
        u.setStatus(User.STATUS_SUSPENDED);
        when(userRepository.findById(suspended)).thenReturn(Optional.of(u));

        var result = service.sweep();

        assertThat(result.saved()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(netWorthService, never()).saveSnapshotForToday(any());
    }

    @Test
    void aVanishedUserIsSkippedRatherThanErroring() {
        // The account row survived (findDistinctUserIds found it) but the user row didn't --
        // an unreachable-in-practice ordering, but the sweep must not NPE if it ever happens.
        UUID ghost = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(ghost));
        when(userRepository.findById(ghost)).thenReturn(Optional.empty());

        var result = service.sweep();

        assertThat(result.skipped()).isEqualTo(1);
        verify(netWorthService, never()).saveSnapshotForToday(any());
    }

    @Test
    void oneFailingUserDoesNotStopTheRestOfTheBatch() {
        UUID poison = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(poison, healthy));
        when(userRepository.findById(poison)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findById(healthy)).thenReturn(Optional.of(activeUser()));
        doThrow(new RuntimeException("boom")).when(netWorthService).saveSnapshotForToday(poison);

        var result = service.sweep();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.saved()).isEqualTo(1);
        verify(netWorthService).saveSnapshotForToday(healthy);
    }

    @Test
    void scheduledSweepDoesNothingWhenTheFlagIsOff() {
        ReflectionTestUtils.setField(service, "sweepEnabled", false);

        service.scheduledSweep();

        verify(accountRepository, never()).findDistinctUserIds();
    }

    private static User activeUser() {
        User u = new User();
        u.setStatus(User.STATUS_ACTIVE);
        return u;
    }
}
