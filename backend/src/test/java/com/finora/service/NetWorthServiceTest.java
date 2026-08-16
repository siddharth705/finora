package com.finora.service;

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
 *
 * <p>Bug fix, second: saveSnapshotForToday() used to be findByUserIdAndSnapshotDate().orElseGet(new)
 * then save(), guarded by a catch(DataIntegrityViolationException) that re-found and re-saved on a
 * lost race. That shape -- write, catch, re-query inside the same call -- is what turned out to
 * poison an ambient transaction in MerchantNormalizationEngine.addAlias; this method only escaped it
 * by accident (no @Transactional anywhere on this call path). It is now
 * NetWorthSnapshotRepository.upsertForToday, an atomic INSERT ... ON CONFLICT DO UPDATE, so there is
 * no race left to simulate here -- see NetWorthSnapshotUpsertIT for real-Postgres coverage of the
 * atomic behaviour itself (correctness of ON CONFLICT DO UPDATE isn't something a mock can prove).
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
    }

    @Test
    void saveSnapshotForToday_writesThroughTheAtomicUpsert_withNoPriorReadOfTheExistingRow() {
        // The property that replaces the old race test: there is no more find-then-save for a
        // concurrent caller to race against. A read here would be exactly the check-then-act this
        // method used to have, reintroduced.
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        service.saveSnapshotForToday(userId);

        verify(snapshotRepository, never()).findByUserIdAndSnapshotDate(any(), any());
        verify(snapshotRepository, never()).save(any());
        verify(snapshotRepository).upsertForToday(eq(userId), any(), any(), any(), any());
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

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(snapshotRepository).upsertForToday(eq(userId), dateCaptor.capture(), any(), any(), any());
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now(ZoneId.of("Pacific/Kiritimati")));
        verify(userRepository).findById(userId);
    }

    @Test
    void saveSnapshotForToday_fallsBackSafely_whenUserHasNoTimezoneSet() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        service.saveSnapshotForToday(userId);

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(snapshotRepository).upsertForToday(eq(userId), dateCaptor.capture(), any(), any(), any());
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")));
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
