package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives EVERY real statement in the local corpus through the whole import -- parse, stage,
 * confirm, persist -- and reports how many completed and why any did not.
 *
 * <h2>This is a local instrument, not a CI gate</h2>
 *
 * The corpus is real customer bank statements and must never be committed (Synthetic Fixture
 * Policy, and the 2026-08-08 incident behind it), so this test cannot run in CI and does not try
 * to: it skips itself unless the corpus directory is present. {@link PdfImportEndToEndIT} is the
 * committed, synthetic proof of the same seam and runs everywhere. This one answers the different
 * question that only real documents can answer -- "would these 29 statements actually import?"
 *
 * <h2>What "imported" means here, precisely</h2>
 *
 * A statement counts as imported when the pipeline parses it, stages at least one transaction,
 * confirm completes without error, and every confirmed row is then found in the database. That last
 * clause is the point: a document that stages fifty rows and persists forty-nine has NOT imported,
 * and no row count taken before confirm would show it.
 *
 * <p>Deliberately NOT asserted here: whether the staged values are CORRECT. That is the
 * ground-truth runner's job ({@code scripts/run-corpus-ground-truth.py}), and conflating the two
 * would let a document that imports garbage cleanly count as a success. This measures reachability
 * of the import path, and says so.
 *
 * <p>Set {@code FINORA_CORPUS_DIR} to point at the corpus; the directory is read recursively, since
 * it is a human-maintained folder that has already been reorganised into per-product subfolders
 * once.
 */
class RealCorpusImportEndToEndIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();

    /**
     * One statement's journey through the import, and what became of it.
     *
     * <p>Three verdicts, not two, and the third is the important one. "Staged nothing" is NOT the
     * same event as "the import path broke": a statement can legitimately report no activity, and
     * at least one in this corpus does -- its ground truth expects zero transactions and it passes
     * the correctness gate. Calling that a failure would report a defect that does not exist, and
     * would also hide a real one behind the same word. Whether zero is CORRECT for a given document
     * is the ground-truth runner's question, not this instrument's; this one only reports that
     * nothing was staged, and refuses to guess which kind of nothing it was.
     */
    private record Outcome(String file, String verdict, int staged, int confirmed, int persisted,
                            String reason) {
        static Outcome imported(String f, int staged, int persisted) {
            return new Outcome(f, "IMPORTED", staged, staged, persisted, "");
        }
        static Outcome nothingToImport(String f) {
            return new Outcome(f, "NO_TXNS", 0, 0, 0,
                    "parsed cleanly, staged no transactions -- correct for a nil-activity "
                            + "statement, unproven otherwise; the ground-truth runner decides which");
        }
        static Outcome failed(String f, int staged, int confirmed, int persisted, String why) {
            return new Outcome(f, "FAILED", staged, confirmed, persisted, why);
        }
    }

    private static Path corpusDir() {
        String configured = System.getenv("FINORA_CORPUS_DIR");
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    private User user(String label) {
        User user = new User();
        user.setEmail("corpus-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName(label);
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private Account account(User owner) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName("Imported");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    /** Every statement in the corpus, matched case-insensitively and read recursively -- the same
     *  discovery rule the corpus scripts use, and for the same two reasons they document. */
    private static List<Path> statements(Path corpus) throws IOException {
        try (Stream<Path> walk = Files.walk(corpus)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private Outcome importOne(Path statement) {
        String name = statement.getFileName().toString();
        int staged = 0;
        int confirmed = 0;
        try {
            byte[] content = Files.readAllBytes(statement);
            User user = user("Corpus " + name);
            Account account = account(user);

            StagingResponse response = importService.parseAndStageAnyFormat(
                    user.getId(), "PDF", name, content, null);
            List<StagedRow> rows = response.rows() == null ? List.of() : response.rows();
            staged = rows.size();
            if (staged == 0) {
                return Outcome.nothingToImport(name);
            }

            List<ConfirmedRow> toConfirm = rows.stream()
                    .map(r -> new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(),
                            r.suggestedCategory() == null ? "Other" : r.suggestedCategory(), true,
                            "rule", null, false, null, null, false))
                    .toList();
            confirmed = toConfirm.size();

            ImportSession session = importSessionService.createSession(
                    user.getId(), name, content, rows, response.detectedAccount());
            importService.confirmSession(user.getId(), new ConfirmRequest(
                    session.getId(), toConfirm, account.getId(), null, null, null, null));

            int persisted = transactionRepository.findByUserId(user.getId()).size();
            if (persisted != confirmed) {
                return Outcome.failed(name, staged, confirmed, persisted,
                        "confirmed " + confirmed + " rows but only " + persisted + " reached the database");
            }
            return Outcome.imported(name, staged, persisted);
        } catch (Exception e) {
            String why = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage().replaceAll("\\s+", " "));
            return Outcome.failed(name, staged, confirmed, 0,
                    why.length() > 160 ? why.substring(0, 160) + "..." : why);
        }
    }

    @Test
    void everyRealStatementImportsEndToEnd() throws Exception {
        Path corpus = corpusDir();
        assumeTrue(corpus != null && Files.isDirectory(corpus),
                "FINORA_CORPUS_DIR is not set to a real corpus directory -- this instrument only "
                        + "runs locally, because the corpus can never be committed");

        List<Path> statements = statements(corpus);
        assumeTrue(!statements.isEmpty(), "no statements found under " + corpus);

        List<Outcome> outcomes = new ArrayList<>();
        for (Path statement : statements) {
            outcomes.add(importOne(statement));
            // Learning events accumulate per import; clear as we go so a 29-document run does not
            // leave a pile of them behind for the next test in this shared database.
            removeQueuedLearningEvents();
        }

        List<Outcome> failures = outcomes.stream().filter(o -> "FAILED".equals(o.verdict())).toList();
        List<Outcome> empty = outcomes.stream().filter(o -> "NO_TXNS".equals(o.verdict())).toList();
        int imported = outcomes.size() - failures.size() - empty.size();

        StringBuilder report = new StringBuilder("\n\nEND-TO-END IMPORT OF THE REAL CORPUS\n");
        report.append("=".repeat(96)).append('\n');
        report.append(String.format("%-42s %-10s %8s %10s   %s%n",
                "statement", "verdict", "staged", "persisted", "reason"));
        report.append("-".repeat(96)).append('\n');
        for (Outcome o : outcomes) {
            report.append(String.format("%-42s %-10s %8d %10d   %s%n",
                    o.file().length() > 41 ? o.file().substring(0, 41) : o.file(),
                    o.verdict(), o.staged(), o.persisted(), o.reason()));
        }
        report.append("-".repeat(96)).append('\n');
        report.append(String.format(
                "%n  OUT OF %d STATEMENTS: %d IMPORTED, %d STAGED NOTHING, %d FAILED%n",
                outcomes.size(), imported, empty.size(), failures.size()));
        report.append(String.format("  transactions persisted in total: %d%n",
                outcomes.stream().mapToInt(Outcome::persisted).sum()));
        report.append("  every confirmed row reached the database on every imported statement.%n"
                .replace("%n", System.lineSeparator()));
        if (!empty.isEmpty()) {
            report.append("\n  STAGED NOTHING (not an import failure -- see Outcome's doc comment)\n");
            for (Outcome e : empty) {
                report.append("    - ").append(e.file()).append('\n');
            }
        }
        if (!failures.isEmpty()) {
            report.append("\n  FAILURES\n");
            for (Outcome f : failures) {
                report.append("    - ").append(f.file()).append(": ").append(f.reason()).append('\n');
            }
        }
        System.out.println(report);

        // The report is the deliverable; this assertion is what makes it a test rather than a
        // script. Only FAILED counts: a statement that throws, or whose confirmed rows do not all
        // arrive, is a real defect regardless of value correctness. A statement that staged nothing
        // is reported and not asserted on, because this instrument cannot tell a nil-activity
        // statement from an unsupported one -- and pretending otherwise is how a green run starts
        // meaning less than it appears to.
        assertThat(failures)
                .as("statements that could not be imported end to end:%n%s", report)
                .isEmpty();
    }

    private void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
    }
}
