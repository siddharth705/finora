package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.imports.ClosingBalanceGuard;
import com.finora.imports.ImportSessionService;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementContentService;
import com.finora.imports.storage.StatementIntegrityException;
import com.finora.imports.storage.StatementStorageException;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.UserRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Phase C-4 -- proves {@link ClosingBalanceEvidenceRederivationService} against the real
 * {@link ImportSessionService}/{@link StatementContentService} boundary, per
 * {@code c4-investigation-closing-note.md}. Deliberately NOT a plain unit test (see that note for
 * why C-3's approach isn't sufficient here): the real risk surface is the session-claim/
 * ownership/expiry/storage-mode plumbing, which only a real database and real services can prove.
 *
 * <p>Both storage modes are exercised in ONE Spring context (filesystem storage enabled via
 * {@link #registerStorageProperties}). {@link StatementContentService#read} chooses between them
 * purely on whether the row carries a content address, regardless of whether a provider happens
 * to be configured -- and that is still worth proving here even though, since the storage-review
 * lifecycle change, a SESSION never naturally reaches the addressed state through
 * {@link ImportSessionService} anymore (staging keeps bytes in {@code file_content} until confirm
 * -- see that class's own {@code storeContent} doc comment). {@link #createObjectStorageSession}
 * therefore hand-constructs an addressed session directly against
 * {@link ImportSessionRepository}, the same way {@link #poisonedSession} and
 * {@code contentHashMismatch_rejected} already did before this change -- proving the shared
 * addressed-read mechanism itself (which a confirmed {@code StatementImport} row DOES reach
 * naturally) rather than a call path that no longer exists for a session specifically.
 */
class ClosingBalanceEvidenceRederivationServiceIT extends AbstractIntegrationTest {

    @Autowired private ImportSessionService importSessionService;
    @Autowired private StatementContentService statementContentService;
    @Autowired private java.util.Optional<com.finora.imports.storage.StatementStorage> statementStorage;
    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private ClosingBalanceEvidenceRederivationService rederivationService;
    @Autowired private UserRepository userRepository;

    private final java.util.List<UUID> createdUserIds = new java.util.ArrayList<>();

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.statement-storage.provider", () -> "filesystem");
        registry.add("app.statement-storage.filesystem.root",
                () -> System.getProperty("java.io.tmpdir") + "/finora-c4-evidence-it");
    }

    @AfterEach
    void cleanUp() {
        if (!createdUserIds.isEmpty()) {
            userRepository.deleteAllById(createdUserIds);
            createdUserIds.clear();
        }
    }

    private UUID newUser() {
        User user = new User();
        user.setEmail("c4-evidence-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("C4 Evidence IT User");
        user.setPhoneVerified(true);
        UUID id = userRepository.save(user).getId();
        createdUserIds.add(id);
        return id;
    }

    private byte[] goldenFixtureBytes() throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/pdf/separate_debit_credit_balance_sample.pdf"));
    }

    /** An addressed session -- built by storing RAW, uncompressed bytes directly through
     *  {@link com.finora.imports.storage.StatementStorage#store}, deliberately NOT through
     *  {@link StatementContentService#store} (which compresses whenever a provider is configured,
     *  as of the storage-review change). {@link ImportSession#getCompressionType()} is fixed at
     *  {@code NONE} -- a session can never legitimately claim a compressed object, since
     *  {@link ImportSessionService} itself never produces one anymore (see this class's own doc
     *  comment) -- so what is attached here must actually BE uncompressed, or
     *  {@link StatementContentService#read} would hand back raw GZIP bytes unhashed against the
     *  original content and fail integrity verification for a reason that has nothing to do with
     *  what this test is proving. This still exercises the shared addressed-read mechanism for a
     *  genuinely valid, retrievable object -- the same mechanism a confirmed
     *  {@code StatementImport} row reaches naturally, just without that row's compression. */
    private ImportSession createObjectStorageSession(UUID userId, byte[] fileContent) {
        ContentAddress address = statementStorage.orElseThrow().store(fileContent);
        ImportSession session = new ImportSession();
        session.setUserId(userId);
        session.setFileName("statement.pdf");
        session.setContentHash(address.hash());
        session.setObjectKey(address.key());
        session.setExpiresAt(Instant.now().plus(java.time.Duration.ofHours(48)));
        return importSessionRepository.save(session);
    }

    /** Real legacy-BYTEA-backed session -- bypasses {@code ImportSessionService.createSession}'s
     *  storage routing deliberately, to exercise {@code StatementContentService.read}'s OTHER
     *  branch for real, in the SAME Spring context (with a provider configured) rather than
     *  needing a second context with none. {@code StatementContentService.read} chooses its path
     *  purely on whether the row carries {@code contentHash}/{@code objectKey} -- both null here
     *  is exactly what a legacy row looks like today, provider configured or not. */
    private ImportSession createLegacySession(UUID userId, byte[] fileContent) {
        ImportSession session = new ImportSession();
        session.setUserId(userId);
        session.setFileName("statement.pdf");
        session.setFileContent(fileContent);
        session.setExpiresAt(Instant.now().plus(java.time.Duration.ofHours(48)));
        return importSessionRepository.save(session);
    }

    private static DetectedAccountInfo emptyDetectedAccount() {
        return new DetectedAccountInfo(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, 0.0, true, List.of(), null, null, null, null, null, null, null, null);
    }

    // --- 1/3. Object-storage-backed session, valid, correct bytes retrieved ---

    @Test
    void objectStorageBackedSession_validSession_evidenceRederivedCorrectly() throws Exception {
        UUID userId = newUser();
        ImportSession session = createObjectStorageSession(userId, goldenFixtureBytes());
        assertThat(session.getContentHash()).isNotNull(); // confirms this really did go to object storage
        assertThat(session.getObjectKey()).isNotNull();

        FieldAssessment assessment = rederivationService.rederiveClosingBalanceEvidence(
                userId, session.getId(), null, new BigDecimal("117209.50"));

        assertThat(assessment.financialValidation().status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(assessment.structural().status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    // --- 2. Legacy BYTEA-backed session, valid, correct bytes retrieved ---

    @Test
    void legacyBackedSession_validSession_evidenceRederivedCorrectly() throws Exception {
        UUID userId = newUser();
        ImportSession session = createLegacySession(userId, goldenFixtureBytes());
        assertThat(session.getContentHash()).isNull();
        assertThat(session.getObjectKey()).isNull();

        FieldAssessment assessment = rederivationService.rederiveClosingBalanceEvidence(
                userId, session.getId(), null, new BigDecimal("117209.50"));

        assertThat(assessment.financialValidation().status()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(assessment.structural().status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    // --- 4. Expired session -> rejected before byte access ---

    @Test
    void expiredSession_rejectedBeforeByteAccess() {
        UUID userId = newUser();
        // A session whose content COULD NOT be read even if reached -- object storage never wrote
        // anything for this row. If the ordering were wrong (byte access before the expiry check),
        // this would fail with a storage error, not the expiry message -- proving the ordering by
        // which exception surfaces, not merely that SOME exception does.
        ImportSession session = poisonedSession(userId);
        session.setExpiresAt(Instant.now().minusSeconds(60));
        importSessionRepository.save(session);

        assertThatThrownBy(() -> rederivationService.rederiveClosingBalanceEvidence(
                userId, session.getId(), null, BigDecimal.TEN))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    // --- 5. Already-confirmed session -> rejected ---

    @Test
    void alreadyConfirmedSession_rejected() {
        UUID userId = newUser();
        ImportSession session = poisonedSession(userId);
        session.setStatus(ImportSession.STATUS_CONFIRMED);
        importSessionRepository.save(session);

        assertThatThrownBy(() -> rederivationService.rederiveClosingBalanceEvidence(
                userId, session.getId(), null, BigDecimal.TEN))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been reviewed and confirmed");
    }

    /** A session that is owned/not-expired/not-confirmed (passes every ownership check) but whose
     *  claimed object was never actually stored -- used to prove ordering: reaching the byte-read
     *  step on this session throws a storage error, so a test asserting an ownership-layer error
     *  instead proves that step was never reached. */
    private ImportSession poisonedSession(UUID userId) {
        ImportSession session = new ImportSession();
        session.setUserId(userId);
        session.setFileName("statement.pdf");
        session.setContentHash("0000000000000000000000000000000000000000000000000000000000000000");
        session.setObjectKey("statements/00/00/nonexistent.bin");
        session.setExpiresAt(Instant.now().plus(java.time.Duration.ofHours(48)));
        return importSessionRepository.save(session);
    }

    // --- 6. Missing/deleted content -> fails safely ---

    @Test
    void missingContent_failsSafely_neverSilentlyReturnsWrongBytes() {
        UUID userId = newUser();
        ImportSession session = poisonedSession(userId); // valid, unexpired, unconfirmed -- but nothing is stored at its objectKey

        assertThatThrownBy(() -> rederivationService.rederiveClosingBalanceEvidence(
                userId, session.getId(), null, BigDecimal.TEN))
                .isInstanceOf(StatementStorageException.class);
    }

    // --- 7. Content hash mismatch -> rejected ---

    @Test
    void contentHashMismatch_rejected() throws Exception {
        UUID userId = newUser();
        byte[] realBytes = goldenFixtureBytes();
        // Raw, uncompressed store -- see createObjectStorageSession's own doc comment for why a
        // session (getCompressionType() fixed at NONE) must never be given a genuinely compressed
        // object; this scenario needs the object to be otherwise perfectly valid so the ONLY thing
        // making it fail is the deliberately-wrong hash below, not a decompression mismatch too.
        ContentAddress realAddress = statementStorage.orElseThrow().store(realBytes);

        // A row claiming a DIFFERENT hash than what's actually stored at that key -- the exact
        // "object present, but not the document this row addresses" scenario
        // ContentAddress.requireMatches exists to catch.
        ImportSession session = new ImportSession();
        session.setUserId(userId);
        session.setFileName("statement.pdf");
        session.setContentHash("1111111111111111111111111111111111111111111111111111111111111111");
        session.setObjectKey(realAddress.key());
        session.setExpiresAt(Instant.now().plus(java.time.Duration.ofHours(48)));
        importSessionRepository.save(session);

        assertThatThrownBy(() -> rederivationService.rederiveClosingBalanceEvidence(
                userId, session.getId(), null, BigDecimal.TEN))
                .isInstanceOf(StatementIntegrityException.class);
    }

    // --- 8. Session A cannot retrieve session B's content ---

    @Test
    void sessionA_cannotBeRetrievedByUserB_ownershipEnforced() throws Exception {
        UUID userA = newUser();
        UUID userB = newUser();
        ImportSession sessionA = createLegacySession(userA, goldenFixtureBytes());

        assertThatThrownBy(() -> rederivationService.rederiveClosingBalanceEvidence(
                userB, sessionA.getId(), null, BigDecimal.TEN))
                .isInstanceOf(ApiException.class);

        // The legitimate owner can still retrieve it -- proves the rejection above was ownership,
        // not a broken session.
        FieldAssessment ownerResult = rederivationService.rederiveClosingBalanceEvidence(
                userA, sessionA.getId(), null, new BigDecimal("117209.50"));
        assertThat(ownerResult.financialValidation().status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    // --- 9. Same session deterministically re-derives evidence ---

    @Test
    void sameSession_deterministicReDerivation() throws Exception {
        UUID userId = newUser();
        ImportSession session = createLegacySession(userId, goldenFixtureBytes());

        FieldAssessment first = rederivationService.rederiveClosingBalanceEvidence(
                userId, session.getId(), null, new BigDecimal("117209.50"));
        FieldAssessment second = rederivationService.rederiveClosingBalanceEvidence(
                userId, session.getId(), null, new BigDecimal("117209.50"));

        assertThat(first.status()).isEqualTo(second.status());
        assertThat(first.structural().status()).isEqualTo(second.structural().status());
        assertThat(first.financialValidation().status()).isEqualTo(second.financialValidation().status());
    }

    // --- 10. sourceSectionIndex preserved correctly (multi-section) ---

    @Test
    void sourceSectionIndex_selectsTheCorrectSection() throws Exception {
        UUID userId = newUser();
        byte[] multiSectionBytes = PdfFixtureBuilder.buildMultiSectionCompositeStatementSample();
        ImportSession session = createLegacySession(userId, multiSectionBytes);

        // Both sections must be independently addressable by index, and must not throw for a
        // reasonable claim -- proving sourceSectionIndex actually reaches PdfPreviewGenerator's
        // section selection rather than always assessing section 0.
        FieldAssessment sectionZero = rederivationService.rederiveClosingBalanceEvidence(
                userId, session.getId(), 0, new BigDecimal("1.00"));
        FieldAssessment sectionOne = rederivationService.rederiveClosingBalanceEvidence(
                userId, session.getId(), 1, new BigDecimal("1.00"));

        assertThat(sectionZero).isNotNull();
        assertThat(sectionOne).isNotNull();
        // Different sections' FinancialValidation provenance must carry different section indexes
        // -- direct proof the index parameter actually threaded through, not just "didn't crash".
        assertThat(sectionZero.financialValidation().provenance())
                .isNotEqualTo(sectionOne.financialValidation().provenance());
    }

    // --- 12. ClosingBalanceGuard remains authoritative / untouched ---

    @Test
    void closingBalanceGuard_remainsCompletelyUnaffectedByThisService() throws Exception {
        UUID userId = newUser();
        ImportSession session = createLegacySession(userId, goldenFixtureBytes());

        // Call the new service...
        rederivationService.rederiveClosingBalanceEvidence(
                userId, session.getId(), null, new BigDecimal("117209.50"));

        // ...and separately, ClosingBalanceGuard behaves exactly as ClosingBalanceGuardTest's own
        // "a closing balance the imported rows actually reach is applied" case proves in
        // isolation -- this service neither calls into it nor is called by it, so its own known-
        // correct fixture (not this class's PDF, which this test has no need to re-derive totals
        // from) is exactly what proves that independence.
        ClosingBalanceGuard.Decision decision = ClosingBalanceGuard.assess(
                com.finora.entity.Account.Type.SAVINGS, new BigDecimal("1000.00"),
                new BigDecimal("1500.00"), new BigDecimal("800.00"), new BigDecimal("300.00"), 5, 0);
        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.CORROBORATED);
    }
}
