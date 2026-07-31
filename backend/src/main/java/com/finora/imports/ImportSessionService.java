package com.finora.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.ImportSession;
import com.finora.exception.ApiException;
import com.finora.repository.ImportSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for staged import review state (ADR-0002 / docs/adr/0002-persisted-import-sessions.md).
 * Deliberately a thin persistence + (de)serialization layer, not orchestration -- ImportService
 * still owns the actual stage/confirm business logic, this just gives it somewhere durable to
 * put the in-between state. Kept in com.finora.imports (not com.finora.service) since it's
 * specific to this module, matching PreviewGenerator/DuplicateDetector/etc.
 */
@Component
public class ImportSessionService {

    // A user has as long as this to come back and confirm a staged import before it's treated as
    // abandoned. No production usage data to tune this against yet (same "revisit once there's
    // real traffic" caveat as StatementImportHealthProvider's own threshold) -- 48h is picked to
    // comfortably cover "I'll finish this tomorrow," not as a carefully measured number.
    private static final Duration SESSION_TTL = Duration.ofHours(48);

    private final ImportSessionRepository importSessionRepository;
    private final ObjectMapper objectMapper;

    public ImportSessionService(ImportSessionRepository importSessionRepository, ObjectMapper objectMapper) {
        this.importSessionRepository = importSessionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ImportSession createSession(UUID userId, String fileName, byte[] fileContent,
                                        List<StagedRow> rows, DetectedAccountInfo detectedAccount) {
        // Opportunistic cleanup: this user's own expired sessions get deleted the next time they
        // start a new import, rather than via a platform-wide @Scheduled sweep -- this codebase
        // has no background job infrastructure yet (see ImportSession's own doc comment). Scoped
        // to just this user's rows (cheap, bounded by their own usage) rather than a full-table
        // scan every time anyone imports anything.
        importSessionRepository.deleteAll(
                importSessionRepository.findByUserIdAndExpiresAtBefore(userId, Instant.now()));

        ImportSession session = new ImportSession();
        session.setUserId(userId);
        session.setFileName(fileName);
        session.setFileContent(fileContent);
        session.setStagedRowsJson(writeJson(rows));
        session.setDetectedAccountJson(writeJson(detectedAccount));
        session.setExpiresAt(Instant.now().plus(SESSION_TTL));
        return importSessionRepository.save(session);
    }

    /** Throws (not Optional) -- every real caller needs a valid, owned, still-staged session to
     *  proceed at all; there's no sensible "continue without one" path for either confirm() or
     *  a resume-fetch, so making every caller null-check would just be the same exception
     *  written out three times instead of once here. */
    @Transactional(readOnly = true)
    public ImportSession getOwnedSession(UUID userId, UUID sessionId) {
        ImportSession session = importSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Import session not found."));
        if (!session.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This import session does not belong to you.");
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This import session has expired -- upload the statement again to continue.");
        }
        if (ImportSession.STATUS_CONFIRMED.equals(session.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This import has already been confirmed.");
        }
        return session;
    }

    @Transactional(readOnly = true)
    public List<ImportSession> listActiveSessions(UUID userId) {
        return importSessionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, ImportSession.STATUS_STAGED)
                .stream()
                .filter(s -> s.getExpiresAt().isAfter(Instant.now()))
                .toList();
    }

    /**
     * Atomically claims a session for confirmation -- the actual fix for the double-submission
     * race a double-click or retried request could trigger (see
     * ImportSessionRepository.claimForConfirmation's own doc comment for why a plain
     * read-then-save wouldn't have been enough). getOwnedSession() first for the ownership/
     * expiry checks (a genuinely unauthorized or expired attempt should fail with a clear reason,
     * not a generic "already confirmed"); the atomic UPDATE afterward is what actually prevents
     * two concurrent legitimate requests from both proceeding. Called as the very first thing
     * confirmSession() does, before any transaction-import work happens at all -- if this method
     * throws, nothing downstream has touched the transactions table yet.
     */
    @Transactional
    public ImportSession claimForConfirmation(UUID userId, UUID sessionId) {
        getOwnedSession(userId, sessionId); // ownership + expiry + not-already-confirmed, for a clear error message
        int updated = importSessionRepository.claimForConfirmation(sessionId);
        if (updated == 0) {
            // Lost the race between the read above and this atomic update -- someone/something
            // else (a concurrent duplicate request for the same session) claimed it first.
            throw new ApiException(HttpStatus.CONFLICT, "This import has already been confirmed.");
        }
        return importSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Import session not found."));
    }

    @Transactional
    public void deleteSession(UUID userId, UUID sessionId) {
        ImportSession session = getOwnedSession(userId, sessionId);
        importSessionRepository.delete(session);
    }

    public List<StagedRow> readStagedRows(ImportSession session) {
        return readJson(session.getStagedRowsJson(), new TypeReference<List<StagedRow>>() {});
    }

    public DetectedAccountInfo readDetectedAccount(ImportSession session) {
        return readJson(session.getDetectedAccountJson(), DetectedAccountInfo.class);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // StagedRow/DetectedAccountInfo are plain records of primitives/BigDecimal/LocalDate
            // -- Jackson serializing them failing at all would mean something is structurally
            // broken (a non-serializable type snuck in), not a normal runtime condition to
            // recover from gracefully.
            throw new IllegalStateException("Failed to serialize import session state", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize import session state", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize import session state", e);
        }
    }
}
