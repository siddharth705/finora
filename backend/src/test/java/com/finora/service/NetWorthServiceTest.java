package com.finora.service;

import com.finora.entity.NetWorthSnapshot;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Bug fix: saveSnapshotForToday() used to call the bare LocalDate.now(), which resolves "today"
 * against the server's JVM default timezone rather than the user's own (User.timezone) -- a user
 * meaningfully east or west of wherever the server happens to run could get the wrong calendar
 * date stamped on a snapshot they clicked "save" on today, from their own point of view. These
 * tests prove the fix actually consults the user's timezone rather than silently ignoring it.
 */
class NetWorthServiceTest {

    private AccountRepository accountRepository;
    private NetWorthSnapshotRepository snapshotRepository;
    private UserRepository userRepository;
    private NetWorthService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        snapshotRepository = mock(NetWorthSnapshotRepository.class);
        userRepository = mock(UserRepository.class);
        service = new NetWorthService(accountRepository, snapshotRepository, userRepository);

        when(accountRepository.findByUserId(any())).thenReturn(List.of());
        when(snapshotRepository.findByUserIdAndSnapshotDate(any(), any())).thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void saveSnapshotForToday_recoversGracefully_whenAConcurrentRequestWinsTheInsertRace() {
        // Bug fix: findByUserIdAndSnapshotDate().orElseGet(new) then save() is a genuine
        // check-then-act race -- two concurrent calls for the same user+day could both see no
        // existing row and both try to INSERT, with net_worth_snapshots(user_id, snapshot_date)'s
        // UNIQUE constraint (V1 migration) letting exactly one through. This simulates being the
        // loser of that race and proves it's handled as the normal "overwrite today's snapshot"
        // path, not an unhandled 500.
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        NetWorthSnapshot winnersRow = new NetWorthSnapshot();
        winnersRow.setUserId(userId);
        winnersRow.setSnapshotDate(LocalDate.now(ZoneId.of("Asia/Kolkata")));

        // First lookup (before attempting the insert): nothing yet, same as the normal path.
        // Second lookup (inside the catch, after losing the race): the concurrent winner's row.
        when(snapshotRepository.findByUserIdAndSnapshotDate(any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        when(snapshotRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"))
                .thenAnswer(inv -> inv.getArgument(0));

        service.saveSnapshotForToday(userId);

        // The retry updated (and saved) the winner's actual row rather than the throwaway one
        // that failed to insert, and did so exactly once more (not looping/retrying endlessly).
        verify(snapshotRepository, times(2)).save(any());
        assertThat(winnersRow.getTotalAssets()).isNotNull();
    }

    @Test
    void saveSnapshotForToday_resolvesTheUsersOwnTimezone_notTheServersDefault() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        // UTC+14 -- as far ahead of UTC as any real IANA zone gets, deliberately chosen so its
        // "today" is essentially guaranteed to differ from LocalDate.now() under the system
        // default zone (almost certainly UTC in CI/prod), making this assertion meaningful rather
        // than coincidentally passing either way.
        user.setTimezone("Pacific/Kiritimati");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.saveSnapshotForToday(userId);

        ArgumentCaptor<NetWorthSnapshot> captor = ArgumentCaptor.forClass(NetWorthSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        assertThat(captor.getValue().getSnapshotDate()).isEqualTo(LocalDate.now(ZoneId.of("Pacific/Kiritimati")));
        verify(userRepository).findById(userId);
    }

    @Test
    void saveSnapshotForToday_fallsBackSafely_whenUserHasNoTimezoneSet() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        service.saveSnapshotForToday(userId);

        ArgumentCaptor<NetWorthSnapshot> captor = ArgumentCaptor.forClass(NetWorthSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        assertThat(captor.getValue().getSnapshotDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    }

    @Test
    void safeZoneId_fallsBackToDefault_onMalformedTimezoneString() {
        // Same defensive-fallback contract as DashboardService.safeZoneId() -- timezone has no
        // format validation on the settings-update path, so a malformed value must fall back
        // rather than throw an uncaught DateTimeException.
        ZoneId zone = ReflectionTestUtils.invokeMethod(service, "safeZoneId", "not-a-real-timezone");
        assertThat(zone).isEqualTo(ZoneId.of("Asia/Kolkata"));
    }

    @Test
    void safeZoneId_resolvesAnyValidIanaZoneName() {
        ZoneId zone = ReflectionTestUtils.invokeMethod(service, "safeZoneId", "America/New_York");
        assertThat(zone).isEqualTo(ZoneId.of("America/New_York"));
    }
}
