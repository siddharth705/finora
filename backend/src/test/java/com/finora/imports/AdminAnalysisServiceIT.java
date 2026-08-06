package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.repository.MerchantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The property that makes admin analysis safe to run: it changes nothing.
 *
 * <p>Not obvious from reading the call site, which is exactly why it needs a test with a real
 * database. Parsing a row runs {@code TransactionNormalizer.normalize} →
 * {@code CategorizationService.suggest} → {@code MerchantNormalizationEngine.resolve}, and
 * {@code resolve} INSERTS a merchant and an alias for a description it has not seen. A tool whose
 * stated purpose is "import nothing" would have quietly written a merchant row per distinct
 * description, attributed to whichever admin ran it.
 *
 * <p>A mock cannot observe this. The writes are real inserts inside a real transaction, and
 * whether they survive is decided by the transaction manager at commit — the same reason
 * {@code StatementAnalysisRecorderIT} uses a real database rather than verifying that
 * {@code save} was called.
 */
class AdminAnalysisServiceIT extends AbstractIntegrationTest {

    @Autowired private AdminAnalysisService adminAnalysisService;
    @Autowired private StatementAnalysisSessionRepository analysisRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private com.finora.repository.UserRepository userRepository;

    /**
     * A real user row, because {@code merchants.user_id} is foreign-keyed to it.
     *
     * <p>The first version of this test used a random UUID and every case failed — not on the
     * assertion, but because the FK violation aborted the JDBC batch and poisoned the whole
     * transaction, so the parse itself came back FAILED. Worth recording: it means the merchant
     * insert is not a harmless side effect the parse shrugs off. If it cannot complete, nothing
     * after it in the same transaction can either.
     */
    private UUID anAdmin() {
        var user = new com.finora.entity.User();
        user.setEmail("layout-studio-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Layout Studio Admin");
        return userRepository.save(user).getId();
    }

    /**
     * Wholly invented merchants and reference numbers. Nothing here came from a real statement --
     * see check-fixture-hygiene.sh, which exists because a real name and UPI reference were once
     * pasted into a fixture from a customer document.
     */
    private static final String CSV = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/07/2026,UPI-ZORBIC TEAHOUSE-0000000001,120.00,,24880.00
            02/07/2026,UPI-QUILLWORTH STATIONERS-0000000002,340.50,,24539.50
            03/07/2026,UPI-MARROWDEEP GROCERS-0000000003,1200.00,,23339.50
            04/07/2026,SALARY CREDIT VANTABLE LABS,,50000.00,73339.50
            """;

    private static MockMultipartFile csvUpload() {
        return new MockMultipartFile("file", "synthetic-statement.csv", "text/csv",
                CSV.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void analysingAStatementLeavesNoMerchantRowsBehind() throws Exception {
        UUID adminId = anAdmin();
        long merchantsBefore = merchantRepository.count();

        String reference = adminAnalysisService.analyze(adminId, csvUpload(), null);

        // Both halves are required, and the second is what stops this passing for the wrong
        // reason. "No merchants were created" is also true of an analysis that parsed nothing at
        // all -- a broken CSV, a header that did not match, an engine that threw on line one. Only
        // the row count proves the engine really walked these descriptions and still wrote nothing.
        var analysis = analysisRepository.findByReference(reference).orElseThrow();
        assertThat(analysis.getRowCount())
                .as("the engine must actually have parsed these rows, or 'no merchants' is vacuous")
                .isEqualTo(4);
        assertThat(merchantRepository.count())
                .as("resolve() inserts a merchant per unseen description; the analysis transaction "
                    + "must roll those back or a diagnostic tool silently pollutes the admin's own data")
                .isEqualTo(merchantsBefore);
    }

    @Test
    void theEvidenceRowSurvivesTheRollbackThatDiscardsEverythingElse() throws Exception {
        // The two requirements pull in opposite directions: the analysis must persist, everything
        // it touched must not. That only works because the recorder writes in REQUIRES_NEW, so the
        // deliberate rollback here cannot reach it.
        UUID adminId = anAdmin();

        String reference = adminAnalysisService.analyze(adminId, csvUpload(), null);

        var analysis = analysisRepository.findByReference(reference).orElseThrow();
        assertThat(analysis.getOutcome()).isEqualTo(StatementAnalysisSession.Outcome.PARSED);
        assertThat(analysis.getSource()).isEqualTo(StatementAnalysisSession.Source.ADMIN_ANALYSIS);
        assertThat(analysis.getLayoutFingerprint()).startsWith("FP-");
        assertThat(analysis.getUserId()).isEqualTo(adminId);
    }

    @Test
    void adminAnalysisIsDistinguishableFromRealUsage() throws Exception {
        // Source exists so reports can separate deliberate probing from what customers actually
        // hit. Until this endpoint existed, ADMIN_ANALYSIS was declared and never written by
        // anything -- an enum value with no producer.
        String reference = adminAnalysisService.analyze(anAdmin(), csvUpload(), null);

        assertThat(analysisRepository.findByReference(reference).orElseThrow().getSource())
                .isEqualTo(StatementAnalysisSession.Source.ADMIN_ANALYSIS);
    }

    @Test
    void aDocumentTheEngineCannotReadIsRecordedRatherThanThrown() throws Exception {
        // The main reason the tool exists. An unreadable document must come back as a FAILED
        // analysis with a reference, not as an exception -- an admin studying a layout that
        // defeats the parser needs the evidence link most precisely when the parse failed.
        var garbage = new MockMultipartFile("file", "not-a-statement.csv", "text/csv",
                "this file has no header row and no columns at all".getBytes(StandardCharsets.UTF_8));

        String reference = adminAnalysisService.analyze(anAdmin(), garbage, null);

        var analysis = analysisRepository.findByReference(reference).orElseThrow();
        assertThat(analysis.getOutcome()).isEqualTo(StatementAnalysisSession.Outcome.FAILED);
        assertThat(analysis.getFailureCode()).isNotNull();
    }
}
