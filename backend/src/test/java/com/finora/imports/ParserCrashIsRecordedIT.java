package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.domain.PageRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * BH-028: a parse that crashes leaves no evidence it happened.
 *
 * <h2>What was established before changing anything</h2>
 *
 * <ol>
 *   <li><b>Where failures originate.</b> {@code PreviewGenerator.generateWithContext} and
 *       {@code PdfPreviewGenerator.generateSectionsWithContext}. Deliberate rejections are
 *       {@code ApiException}; unintentional ones are unchecked exceptions out of PDFBox and out of
 *       indexing in the locator. There are zero explicit throw sites for the latter, which is
 *       precisely why they are the unanticipated case.</li>
 *   <li><b>Transaction boundary.</b> {@code parseAndStageWithSession} carries no
 *       {@code @Transactional}. {@code StatementAnalysisRecorder}'s methods are
 *       {@code REQUIRES_NEW}, so an evidence row commits independently of everything around it --
 *       recording a failure can never roll anything back, and nothing can roll it back either.</li>
 *   <li><b>What is persisted on failure today.</b> For an {@code ApiException}, a committed
 *       {@code recordFailed} row. For anything else, nothing.</li>
 *   <li><b>Visibility.</b> An unchecked exception reaches
 *       {@code GlobalExceptionHandler.handleGeneric} as a 500 with a stack trace in the log. The
 *       operator gets a log line; the evidence table -- the thing that exists to answer "which
 *       layouts defeat the parser" -- gets nothing, so the failure carries no fingerprint and no
 *       diagnostics.</li>
 *   <li><b>Retry.</b> Identical: same crash, still no row. The gap is permanent, not transient.</li>
 *   <li><b>Partial success.</b> Not from a parse crash -- nothing is written before the parse
 *       returns. See the note on the ordering gap at the end of this class.</li>
 * </ol>
 *
 * <p><b>Desired failure state, defined before implementing it:</b> any failure to parse a document
 * produces exactly one evidence row, whatever the exception type, carrying whatever was learned
 * before the failure (fingerprint and diagnostics when the document got far enough to have them),
 * and the original exception still reaches the caller unchanged. Recording evidence must never
 * convert one failure into a different one.
 */
class ParserCrashIsRecordedIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private UserRepository userRepository;
    @Autowired private StatementAnalysisSessionRepository analysisRepository;

    @SpyBean private PreviewGenerator previewGenerator;

    private static final String CSV = """
            Date,Description,Amount,Type
            2026-07-10,SWIGGY ORDER,486.00,DEBIT
            """;

    private User user() {
        User user = new User();
        user.setEmail("parser-crash-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Parser Crash User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private List<StatementAnalysisSession> analysesFor(User user) {
        return analysisRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 200)).stream()
                .filter(a -> user.getId().equals(a.getUserId()))
                .toList();
    }

    @Test
    @DisplayName("BH-028: a parser crash is recorded as a failed analysis, not silently lost")
    void anUncheckedExceptionFromTheParserStillProducesEvidence() throws Exception {
        User user = user();

        // The shape that actually happens: not a deliberate rejection, but the parser falling over
        // on a document it could not cope with. An IndexOutOfBounds out of column bucketing is the
        // realistic one; the type is not what matters, only that it is not an ApiException.
        doThrow(new IndexOutOfBoundsException("Index 7 out of bounds for length 4"))
                .when(previewGenerator).generateWithContext(any(), any(), any());

        assertThatThrownBy(() -> importService.parseAndStageWithSession(
                user.getId(), "statement.csv", CSV.getBytes(StandardCharsets.UTF_8)))
                .as("the original failure must still reach the caller -- recording evidence is not "
                        + "a licence to convert one exception into another")
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessageContaining("out of bounds");

        List<StatementAnalysisSession> analyses = analysesFor(user);
        assertThat(analyses)
                .as("exactly one row: the failure happened once, so it is recorded once")
                .hasSize(1);
        assertThat(analyses.get(0).getOutcome())
                .isEqualTo(StatementAnalysisSession.Outcome.FAILED);
    }

    @Test
    @DisplayName("a deliberate rejection is still recorded exactly once, and unchanged")
    void anApiExceptionKeepsItsExistingBehaviour() throws Exception {
        User user = user();

        // The path that already worked. Asserted so the fix cannot quietly double-record: a
        // broadened catch that also caught what an inner catch already handled would produce two
        // rows for one failure, and the evidence table's whole value is that its counts mean
        // something.
        assertThatThrownBy(() -> importService.parseAndStageWithSession(
                user.getId(), "statement.csv", "not,a,statement\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class);

        assertThat(analysesFor(user))
                .as("one failure, one row -- not two")
                .hasSize(1);
        assertThat(analysesFor(user).get(0).getOutcome())
                .isEqualTo(StatementAnalysisSession.Outcome.FAILED);
    }

    @Test
    @DisplayName("NEGATIVE: a successful parse is not recorded as a failure")
    void aSuccessfulParseIsNotRecordedAsFailed() throws Exception {
        User user = user();

        importService.parseAndStageWithSession(
                user.getId(), "statement.csv", CSV.getBytes(StandardCharsets.UTF_8));

        assertThat(analysesFor(user))
                .as("a broadened catch that swallowed the success path would show up here")
                .hasSize(1);
        assertThat(analysesFor(user).get(0).getOutcome())
                .as("PARSED, not FAILED -- the point of the fix is to record more failures, not to "
                        + "start calling successes failures")
                .isEqualTo(StatementAnalysisSession.Outcome.PARSED);
    }
}
