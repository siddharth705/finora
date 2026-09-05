package com.finora.service;

import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Track C/C8's sweep control flow -- what gets saved, skipped, or left to retry on the next pass.
 * {@code NetWorthService.snapshotForTodayOnly} itself (the per-user timezone resolution, the
 * upsert) is already covered by {@code saveSnapshotForToday}'s own tests, which it shares its
 * computation with; this proves only that the sweep discovers the right candidates via one batch
 * query and that one user's failure never takes the batch down with it.
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
        List<UUID> candidates = List.of(user1, user2);
        when(accountRepository.findDistinctUserIds()).thenReturn(candidates);
        when(userRepository.findByIdInAndStatus(candidates, User.STATUS_ACTIVE))
                .thenReturn(List.of(activeUser(user1, "Asia/Kolkata"), activeUser(user2, "America/New_York")));

        var result = service.sweep();

        assertThat(result.saved()).isEqualTo(2);
        assertThat(result.skipped()).isZero();
        assertThat(result.failed()).isZero();
        verify(netWorthService).snapshotForTodayOnly(user1, "Asia/Kolkata");
        verify(netWorthService).snapshotForTodayOnly(user2, "America/New_York");
    }

    // The fix this guards: the ACTIVE filter runs in ONE batch query (findByIdInAndStatus), not
    // as a per-candidate findById + status check inside the loop.
    @Test
    void filtersActiveStatusAtTheQueryLayerNotPerCandidateInTheLoop() {
        List<UUID> candidates = List.of(UUID.randomUUID());
        when(accountRepository.findDistinctUserIds()).thenReturn(candidates);
        when(userRepository.findByIdInAndStatus(any(), any())).thenReturn(List.of());

        service.sweep();

        verify(userRepository).findByIdInAndStatus(eq(candidates), eq(User.STATUS_ACTIVE));
        verify(userRepository, never()).findById(any());
    }

    @Test
    void aUserWithNoAccountIsNeverConsidered() {
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of());
        when(userRepository.findByIdInAndStatus(List.of(), User.STATUS_ACTIVE)).thenReturn(List.of());

        var result = service.sweep();

        assertThat(result.saved()).isZero();
        verify(netWorthService, never()).snapshotForTodayOnly(any(), any());
    }

    @Test
    void aNonActiveUserIsSkippedNotSnapshotted() {
        UUID suspended = UUID.randomUUID();
        List<UUID> candidates = List.of(suspended);
        when(accountRepository.findDistinctUserIds()).thenReturn(candidates);
        // A suspended user simply isn't in the ACTIVE-filtered result the query returns.
        when(userRepository.findByIdInAndStatus(candidates, User.STATUS_ACTIVE)).thenReturn(List.of());

        var result = service.sweep();

        assertThat(result.saved()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(netWorthService, never()).snapshotForTodayOnly(any(), any());
    }

    @Test
    void aVanishedUserIsSkippedRatherThanErroring() {
        // The account row survived (findDistinctUserIds found it) but the user row didn't --
        // an unreachable-in-practice ordering, but the sweep must not NPE if it ever happens.
        UUID ghost = UUID.randomUUID();
        List<UUID> candidates = List.of(ghost);
        when(accountRepository.findDistinctUserIds()).thenReturn(candidates);
        when(userRepository.findByIdInAndStatus(candidates, User.STATUS_ACTIVE)).thenReturn(List.of());

        var result = service.sweep();

        assertThat(result.skipped()).isEqualTo(1);
        verify(netWorthService, never()).snapshotForTodayOnly(any(), any());
    }

    @Test
    void oneFailingUserDoesNotStopTheRestOfTheBatch() {
        UUID poison = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        List<UUID> candidates = List.of(poison, healthy);
        when(accountRepository.findDistinctUserIds()).thenReturn(candidates);
        when(userRepository.findByIdInAndStatus(candidates, User.STATUS_ACTIVE))
                .thenReturn(List.of(activeUser(poison, "UTC"), activeUser(healthy, "UTC")));
        doThrow(new RuntimeException("boom")).when(netWorthService).snapshotForTodayOnly(poison, "UTC");

        var result = service.sweep();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.saved()).isEqualTo(1);
        verify(netWorthService).snapshotForTodayOnly(healthy, "UTC");
    }

    @Test
    void scheduledSweepDoesNothingWhenTheFlagIsOff() {
        ReflectionTestUtils.setField(service, "sweepEnabled", false);

        service.scheduledSweep();

        verify(accountRepository, never()).findDistinctUserIds();
    }

    private static User activeUser(UUID id, String timezone) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setStatus(User.STATUS_ACTIVE);
        u.setTimezone(timezone);
        return u;
    }
}
