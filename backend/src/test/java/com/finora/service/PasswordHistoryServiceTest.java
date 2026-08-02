package com.finora.service;

import com.finora.entity.PasswordHistory;
import com.finora.exception.ApiException;
import com.finora.repository.PasswordHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Blocks the "Password1 -> Password2 -> Password1" cycle -- see PasswordHistoryService's own
 *  class doc for why this is a dedicated service rather than copy-pasted into each caller. */
class PasswordHistoryServiceTest {

    private PasswordHistoryRepository repository;
    private PasswordEncoder passwordEncoder;
    private PasswordHistoryService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(PasswordHistoryRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new PasswordHistoryService(repository, passwordEncoder);
        when(repository.save(any(PasswordHistory.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PasswordHistory entryWithHash(String hash) {
        PasswordHistory entry = new PasswordHistory();
        entry.setUserId(userId);
        entry.setPasswordHash(hash);
        return entry;
    }

    @Test
    void rejectIfRecentlyUsed_whenNewPasswordMatchesAnOlderHash_throws() {
        when(repository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(entryWithHash("hash-3"), entryWithHash("hash-2"), entryWithHash("hash-1")));
        when(passwordEncoder.matches("Password1", "hash-3")).thenReturn(false);
        when(passwordEncoder.matches("Password1", "hash-2")).thenReturn(false);
        when(passwordEncoder.matches("Password1", "hash-1")).thenReturn(true);

        assertThatThrownBy(() -> service.rejectIfRecentlyUsed(userId, "Password1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("used this password recently");
    }

    @Test
    void rejectIfRecentlyUsed_whenNewPasswordMatchesNoHistory_doesNotThrow() {
        when(repository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(entryWithHash("hash-1")));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        service.rejectIfRecentlyUsed(userId, "BrandNewPassword123");
        // No exception -- the point of this test.
    }

    @Test
    void rejectIfRecentlyUsed_onlyChecksTheMostRecentFiveEntries() {
        // A 6th, oldest entry that WOULD match -- must not be checked, since only the most
        // recent 5 count as "recently used".
        List<PasswordHistory> sixEntries = new ArrayList<>();
        for (int i = 5; i >= 1; i--) sixEntries.add(entryWithHash("recent-hash-" + i));
        sixEntries.add(entryWithHash("stale-hash-6"));
        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(sixEntries);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(passwordEncoder.matches("OldPassword", "stale-hash-6")).thenReturn(true);

        service.rejectIfRecentlyUsed(userId, "OldPassword");
        // No exception -- the 6th entry is outside the checked window.

        verify(passwordEncoder, never()).matches("OldPassword", "stale-hash-6");
    }

    @Test
    void record_savesTheNewHash() {
        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        service.record(userId, "new-hash");

        ArgumentCaptor<PasswordHistory> captor = ArgumentCaptor.forClass(PasswordHistory.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void record_prunesEntriesBeyondTheMostRecentFive() {
        // Most-recent-first, matching what findByUserIdOrderByCreatedAtDesc actually returns --
        // index 0 is the newest ("hash-6"), index 5 (the 6th, beyond the 5-entry limit) is the
        // oldest ("hash-1") and is the one that must get pruned.
        List<PasswordHistory> sixEntries = new ArrayList<>();
        for (int i = 6; i >= 1; i--) {
            PasswordHistory entry = entryWithHash("hash-" + i);
            ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
            entry.setUserId(userId);
            sixEntries.add(entry);
        }
        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(sixEntries);

        service.record(userId, "hash-1");

        ArgumentCaptor<List<PasswordHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).deleteAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getPasswordHash()).isEqualTo("hash-1");
    }

    @Test
    void record_doesNotPruneWhenAtOrUnderTheLimit() {
        when(repository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(entryWithHash("hash-1"), entryWithHash("hash-2")));

        service.record(userId, "hash-2");

        verify(repository, never()).deleteAll(anyList());
    }
}
