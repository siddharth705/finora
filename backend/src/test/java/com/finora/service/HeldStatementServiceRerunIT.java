package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.HeldStatementRerunResultDto;
import com.finora.entity.HeldStatement;
import com.finora.entity.HeldStatementEvent;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.imports.analysis.ImportVerificationFindingRepository;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.HeldStatementEventRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HeldStatementService#recordFindings} and {@link HeldStatementService#rerunParser} --
 * Plan 3 of the Held Statement Review System. Uses real, storage-backed bytes (BH-045:
 * {@code ImportJob.getFileContent()} always returns null) and real CSV parsing throughout, same
 * discipline as {@code AdminHeldStatementDownloadIT}.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-held-statement-rerun-it"
})
class HeldStatementServiceRerunIT extends AbstractIntegrationTest {

    @Autowired private HeldStatementService heldStatementService;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private HeldStatementEventRepository eventRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private ImportVerificationFindingRepository findingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StatementStorage storage;

    private static final byte[] CLEAN_CSV = ("Date,Description,Amount,Balance\n"
            + "01/01/2026,Opening balance,,1000.00\n"
            + "05/01/2026,Coffee shop,-150.00,850.00\n").getBytes(StandardCharsets.UTF_8);

    /** Statement period 2030 is safely in the future relative to any real "today" this suite will
     *  ever run on -- a robust, non-flaky trigger for TrustPredicate's periodIntegrity check. */
    private static final byte[] FUTURE_PERIOD_CSV = ("Date,Description,Amount,Balance,Statement Period\n"
            + "01/01/2026,Opening balance,,1000.00,01/01/2030 to 31/01/2030\n"
            + "05/01/2026,Coffee shop,-150.00,850.00,\n").getBytes(StandardCharsets.UTF_8);

    /** Period end (20/01/2026) is AFTER the hold's own createdAt (10/01/2026, backdated in the
     *  test) but well BEFORE the real date this suite runs on -- the exact shape that only a
     *  today-anchored evaluation flags correctly. */
    private static final byte[] NEAR_FUTURE_PERIOD_CSV = ("Date,Description,Amount,Balance,Statement Period\n"
            + "01/01/2026,Opening balance,,1000.00,10/01/2026 to 20/01/2026\n"
            + "05/01/2026,Coffee shop,-150.00,850.00,\n").getBytes(StandardCharsets.UTF_8);

    private User user() {
        User user = new User();
        user.setEmail("rerun-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Rerun IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** {@code held_statement_events.actor_id} has a real FK to {@code users(id)} (V144) -- a
     *  random UUID is refused, not merely unrealistic. Every call into the service under test
     *  needs a real, persisted admin id. */
    private UUID admin() {
        return user().getId();
    }

    private HeldStatement seedHold(byte[] bytes) {
        User owner = user();
        ContentAddress address = storage.store(bytes);
        ImportJob job = new ImportJob(owner.getId(), "statement.csv", address.hash(), address.key(), "CSV");
        job.markClaimed("worker", Instant.now());
        UUID sessionId = UUID.randomUUID();
        job.holdForTrustReview(sessionId, null, Instant.now());
        importJobRepository.save(job);

        // One save, matching HeldStatementService.openHold's own sequence exactly (construct,
        // recordSnapshot, recordBank, THEN save) -- two separate saves here previously left the
        // in-memory entity's @Version out of step with what seedHoldWithCreatedAt's later save
        // expected, and Hibernate correctly rejected the stale write.
        HeldStatement held = new HeldStatement(
                "HLD-2026-9" + System.nanoTime() % 100000, job.getId(), owner.getId(), job.getObjectKey(),
                "Printed and parsed transaction count disagree (ROW_GROUPING)");
        held.recordSnapshot("old-build", null, null, null);
        held = heldStatementRepository.save(held);
        job.holdForTrustReview(sessionId, held.getId(), Instant.now());
        importJobRepository.save(job);
        return held;
    }

    private HeldStatement seedHoldWithCreatedAt(byte[] bytes, Instant createdAt) {
        HeldStatement held = seedHold(bytes);
        ReflectionTestUtils.setField(held, "createdAt", createdAt);
        return heldStatementRepository.save(held);
    }

    // --- recordFindings ---------------------------------------------------------------------------

    @Test
    void recordFindingsSavesBothFieldsAndWritesAnEvent() {
        HeldStatement held = seedHold(CLEAN_CSV);

        var result = heldStatementService.recordFindings(admin(), held.getHeldId(),
                "Two-line HSBC header confused the column locator", "PR #950");

        assertThat(result.rootCause()).isEqualTo("Two-line HSBC header confused the column locator");
        assertThat(result.fixReference()).isEqualTo("PR #950");
        List<HeldStatementEvent> events = eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId());
        assertThat(events).extracting(HeldStatementEvent::getEventType).contains("FINDINGS_UPDATED");
    }

    // --- rerunParser: clearing --------------------------------------------------------------------

    @Test
    void rerunParserMarksReadyForImportWhenTheCurrentBuildNoLongerTriggersTheHold() {
        HeldStatement held = seedHold(CLEAN_CSV);

        HeldStatementRerunResultDto result = heldStatementService.rerunParser(admin(), held.getHeldId());

        assertThat(result.stillHeld()).isFalse();
        assertThat(result.reasons()).isEmpty();
        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.READY_FOR_IMPORT);
    }

    @Test
    void rerunParserWritesNoNewVerificationFindingRows() {
        HeldStatement held = seedHold(CLEAN_CSV);
        long findingsBefore = findingRepository.count();

        heldStatementService.rerunParser(admin(), held.getHeldId());

        assertThat(findingRepository.count()).isEqualTo(findingsBefore);
    }

    @Test
    void rerunParserIsIdempotentOnAnAlreadyClearedHold() {
        HeldStatement held = seedHold(CLEAN_CSV);
        heldStatementService.rerunParser(admin(), held.getHeldId());

        HeldStatementRerunResultDto second = heldStatementService.rerunParser(admin(), held.getHeldId());

        assertThat(second.stillHeld()).isFalse();
        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.READY_FOR_IMPORT);
    }

    // --- rerunParser: still held -------------------------------------------------------------------

    @Test
    void rerunParserLeavesTheHoldAloneWhenTheProblemStillReproduces() {
        HeldStatement held = seedHold(FUTURE_PERIOD_CSV);

        HeldStatementRerunResultDto result = heldStatementService.rerunParser(admin(), held.getHeldId());

        assertThat(result.stillHeld()).isTrue();
        assertThat(result.reasons()).isNotEmpty();
        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.HELD);
    }

    // --- rerunParser: anchored today ---------------------------------------------------------------

    @Test
    void rerunParserAnchorsPeriodIntegrityToTheOriginalHoldDateNotToday() {
        Instant heldAt = Instant.parse("2026-01-10T00:00:00Z");
        HeldStatement held = seedHoldWithCreatedAt(NEAR_FUTURE_PERIOD_CSV, heldAt);

        HeldStatementRerunResultDto result = heldStatementService.rerunParser(admin(), held.getHeldId());

        assertThat(result.stillHeld()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("future"));
    }

    // --- rerunParser: event richness ---------------------------------------------------------------

    @Test
    void rerunParserEventRecordsBothParserVersionsAndWhetherTheyChanged() {
        HeldStatement held = seedHold(CLEAN_CSV);

        heldStatementService.rerunParser(admin(), held.getHeldId());

        List<HeldStatementEvent> events = eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId());
        HeldStatementEvent rerun = events.stream()
                .filter(e -> "PARSER_RERUN".equals(e.getEventType())).findFirst().orElseThrow();
        assertThat(rerun.getNotes()).contains("old-build");
        assertThat(rerun.getNotes()).containsIgnoringCase("parser version");
    }

    // --- rerunParser: refuses an already-resolved hold --------------------------------------------

    @Test
    void rerunParserRefusesAnAlreadyResolvedHoldWithAConflictRatherThanCorruptingState() {
        // A rerun attempted after another actor already resolved the same hold (e.g. a concurrent
        // approve that landed first) must not silently proceed -- refuseIfResolved's own 409 is
        // what stops it, exactly the same guard approve/reject already rely on.
        HeldStatement held = seedHold(CLEAN_CSV);
        heldStatementService.approve(admin(), held.getHeldId(), null);

        assertThatThrownBy(() -> heldStatementService.rerunParser(admin(), held.getHeldId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be");

        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.IMPORTED);
    }

    // --- HeldStatement.version: genuine optimistic-locking coverage --------------------------------

    @Test
    void concurrentWritesToTheSameHeldStatementAreCaughtByOptimisticLocking() {
        // Two separately-loaded copies of the same row, simulating two admins who both opened the
        // hold before either saved -- exactly the scenario @Version exists for. The first save
        // advances the row's version; the second, now-stale, save must be rejected rather than
        // silently overwriting the first admin's change.
        HeldStatement held = seedHold(CLEAN_CSV);
        UUID id = held.getId();

        HeldStatement copyA = heldStatementRepository.findById(id).orElseThrow();
        HeldStatement copyB = heldStatementRepository.findById(id).orElseThrow();
        assertThat(copyA.getVersion()).isEqualTo(copyB.getVersion());

        copyA.addNotes("first admin's note");
        heldStatementRepository.saveAndFlush(copyA);

        copyB.addNotes("second admin's stale note");
        assertThatThrownBy(() -> heldStatementRepository.saveAndFlush(copyB))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
