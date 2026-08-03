package com.finora.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.ImportSession;
import com.finora.exception.ApiException;
import com.finora.repository.ImportSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ADR-0002 -- covers the actual safety properties persisted import sessions exist to provide:
 * a session can't be read/confirmed/deleted by anyone but its owner, an expired or
 * already-confirmed session can't be resumed, and expired sessions actually get cleaned up
 * (opportunistically, on the same user's next stage -- see the class's own doc comment for why
 * not a @Scheduled sweep).
 */
class ImportSessionServiceTest {

    private ImportSessionRepository importSessionRepository;
    private ImportSessionService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        importSessionRepository = mock(ImportSessionRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new ImportSessionService(importSessionRepository, objectMapper);
        when(importSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private StagedRow sampleRow() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", "rule", null, false, null, null);
    }

    private DetectedAccountInfo sampleDetected() {
        return new DetectedAccountInfo("Test Bank", "SAVINGS", new BigDecimal("1000"), new BigDecimal("900"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, null, null, null, null, null, null,
                "SAVINGS", 0.85, false, java.util.List.of());
    }

    private ImportSession sessionOwnedBy(UUID owner, Instant expiresAt, String status) {
        ImportSession session = new ImportSession();
        org.springframework.test.util.ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        session.setUserId(owner);
        session.setFileName("statement.csv");
        session.setFileContent(new byte[]{1, 2, 3});
        session.setStagedRowsJson("[]");
        session.setDetectedAccountJson("{}");
        session.setExpiresAt(expiresAt);
        session.setStatus(status);
        return session;
    }

    @Test
    void createSession_persistsSerializedRowsAndDetectedAccount_andDeletesThisUsersOwnExpiredSessionsFirst() {
        when(importSessionRepository.findByUserIdAndExpiresAtBefore(eq(userId), any())).thenReturn(List.of());

        ImportSession created = service.createSession(userId, "statement.csv", new byte[]{1, 2, 3},
                List.of(sampleRow()), sampleDetected());

        assertThat(created.getUserId()).isEqualTo(userId);
        assertThat(created.getStagedRowsJson()).contains("Coffee Shop");
        assertThat(created.getDetectedAccountJson()).contains("Test Bank");
        assertThat(created.getStatus()).isEqualTo(ImportSession.STATUS_STAGED);
        assertThat(created.getExpiresAt()).isAfter(Instant.now());
        verify(importSessionRepository).findByUserIdAndExpiresAtBefore(eq(userId), any());
    }

    @Test
    void createSession_deletesOnlyThisUsersExpiredSessions_notAnyoneElses() {
        ImportSession theirsExpired = sessionOwnedBy(userId, Instant.now().minusSeconds(60), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findByUserIdAndExpiresAtBefore(eq(userId), any())).thenReturn(List.of(theirsExpired));

        service.createSession(userId, "statement.csv", new byte[0], List.of(), sampleDetected());

        verify(importSessionRepository).deleteAll(List.of(theirsExpired));
        // Never queried on behalf of any other user -- this is scoped per-user cleanup, not a
        // platform-wide sweep.
        verify(importSessionRepository, never()).findByUserIdAndExpiresAtBefore(eq(otherUserId), any());
    }

    @Test
    void getOwnedSession_rejectsSomeoneElsesSession() {
        ImportSession theirs = sessionOwnedBy(otherUserId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findById(any())).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.getOwnedSession(userId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not belong to you");
    }

    @Test
    void getOwnedSession_rejectsAnExpiredSession() {
        ImportSession expired = sessionOwnedBy(userId, Instant.now().minusSeconds(60), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findById(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.getOwnedSession(userId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void getOwnedSession_rejectsAnAlreadyConfirmedSession() {
        ImportSession confirmed = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_CONFIRMED);
        when(importSessionRepository.findById(any())).thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> service.getOwnedSession(userId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been confirmed");
    }

    @Test
    void getOwnedSession_returnsIt_whenOwnedUnexpiredAndStillStaged() {
        ImportSession valid = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findById(any())).thenReturn(Optional.of(valid));

        assertThat(service.getOwnedSession(userId, UUID.randomUUID())).isEqualTo(valid);
    }

    @Test
    void listActiveSessions_excludesExpiredOnesEvenIfStillMarkedStaged() {
        // Belt-and-suspenders against the opportunistic cleanup not having run yet for this user
        // -- listActiveSessions() shouldn't show a session as "resumable" just because nothing
        // has deleted it yet.
        ImportSession stillValid = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        ImportSession notYetCleanedUp = sessionOwnedBy(userId, Instant.now().minusSeconds(60), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, ImportSession.STATUS_STAGED))
                .thenReturn(List.of(stillValid, notYetCleanedUp));

        List<ImportSession> active = service.listActiveSessions(userId);

        assertThat(active).containsExactly(stillValid);
    }

    @Test
    void claimForConfirmation_flipsStatusAtomically_whenSessionIsStillStaged() {
        UUID sessionId = UUID.randomUUID();
        ImportSession staged = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        ImportSession nowConfirmed = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_CONFIRMED);
        when(importSessionRepository.findById(sessionId)).thenReturn(Optional.of(staged), Optional.of(nowConfirmed));
        when(importSessionRepository.claimForConfirmation(sessionId)).thenReturn(1);

        ImportSession result = service.claimForConfirmation(userId, sessionId);

        assertThat(result.getStatus()).isEqualTo(ImportSession.STATUS_CONFIRMED);
        verify(importSessionRepository).claimForConfirmation(sessionId);
    }

    @Test
    void claimForConfirmation_rejectsALostRace_whenTheAtomicUpdateAffectsZeroRows() {
        // This is the actual scenario the atomic UPDATE exists for: getOwnedSession() above
        // legitimately saw STAGED (a concurrent request hadn't committed yet), but by the time
        // the atomic UPDATE runs, that other request already flipped it to CONFIRMED. A bare
        // read-then-save wouldn't catch this -- the whole point of claimForConfirmation() is that
        // it does.
        UUID sessionId = UUID.randomUUID();
        ImportSession staged = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findById(sessionId)).thenReturn(Optional.of(staged));
        when(importSessionRepository.claimForConfirmation(sessionId)).thenReturn(0);

        assertThatThrownBy(() -> service.claimForConfirmation(userId, sessionId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been confirmed");
    }

    @Test
    void readStagedRows_roundTripsWhatCreateSessionWrote() {
        ImportSession session = new ImportSession();
        session.setStagedRowsJson("[{\"date\":\"2026-07-01\",\"description\":\"Coffee Shop\",\"amount\":150.00,"
                + "\"type\":\"EXPENSE\",\"suggestedCategory\":\"Food & Dining\",\"categorySource\":\"rule\","
                + "\"ruleId\":null,\"likelyDuplicate\":false}]");

        List<StagedRow> rows = service.readStagedRows(session);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).description()).isEqualTo("Coffee Shop");
        assertThat(rows.get(0).amount()).isEqualByComparingTo("150.00");
    }
}
