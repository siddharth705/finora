package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.imports.jobs.StagedForJob;
import com.finora.imports.jobs.VerificationTelemetry;
import com.finora.imports.trust.HoldDecision;
import com.finora.imports.trust.TrustPredicate;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the trust rules would say about every real statement in the corpus.
 *
 * <p>This is the calibration instrument for Phase 1. The question it answers -- <i>if a trust rule
 * gated on some threshold, how often would it fire, and on what?</i> -- has had no answer at all,
 * which is why every candidate threshold so far would have been a guess.
 *
 * <h2>Why this can substitute for production traffic</h2>
 *
 * <p>It runs the same two steps the worker runs, in the same order:
 * {@link StagedForJob#of} then {@link VerificationTelemetry#from}. That is not an approximation of
 * the production aggregation, it <em>is</em> the production aggregation -- the same pure function
 * over the same reports. So the distribution reported here is what {@code import_jobs} would have
 * recorded had these statements been imported through the queue.
 *
 * <p>Extracting {@code VerificationTelemetry} out of the entity is what makes this possible. It was
 * moved to satisfy an architecture rule; the side effect is that the aggregation can be applied
 * anywhere, with no job, no queue and no database write.
 *
 * <h2>What it is not</h2>
 *
 * <p><b>Not a representative sample.</b> The corpus is real statements from a handful of banks that
 * happened to be collected. It measures how these rules behave on <em>these</em> banks. A threshold
 * derived from it is a starting point, not a calibrated one, and will need revisiting against
 * whatever real users actually upload.
 *
 * <p><b>Not proof the worker writes anything.</b> This exercises staging and the aggregation, not
 * {@code ImportJobWorker}'s persistence path. That still needs one real import through the queue.
 * The two are complementary.
 *
 * <p>Set {@code FINORA_CORPUS_DIR} to run it. Skips itself otherwise -- the corpus is real customer
 * bank statements and can never be committed, so this instrument only ever runs locally, the same
 * discipline {@link RealCorpusImportEndToEndIT} follows.
 */
class CorpusTrustTelemetryCalibrationIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private UserRepository userRepository;

    /** One statement's trust verdict. {@code status} is null when verification produced no report
     *  at all, which is a different fact from a report that found nothing -- and the one this
     *  instrument's assertion is about. */
    private record Verdict(String file, ImportReliabilityStatus status, String textSource,
                            boolean headerUncertain, int findings, int failed, int warning,
                            int stagedRows, String error, boolean held, String holdReasons,
                            String notableRules) {

        String display() {
            if (error != null) return "ERROR";
            return status == null ? "NO_REPORT" : status.name();
        }
    }

    private static Path corpusDir() {
        String configured = System.getenv("FINORA_CORPUS_DIR");
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    /** Same recursive, case-insensitive discovery rule the other corpus instruments use -- the
     *  folder is human-maintained and has been reorganised into per-product subfolders once. */
    private static List<Path> statements(Path corpus) throws IOException {
        try (Stream<Path> walk = Files.walk(corpus)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private User user() {
        User user = new User();
        user.setEmail("calibration-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Corpus Calibration");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /**
     * The rules that did not pass, named.
     *
     * <p>Counts alone cannot answer the calibration question that matters once a gate exists: when
     * a document is NEEDS_ATTENTION and the gate still lets it through, WHICH signal fired and why
     * is it not one of the three the predicate acts on. Without the names that has to be
     * reconstructed by hand from a debugger.
     */
    private static String notableRulesOf(StagedForJob staged) {
        return staged.verificationReports().stream()
                .filter(r -> r != null && r.findings() != null)
                .flatMap(r -> r.findings().stream())
                .filter(f -> f != null && !"VERIFIED".equals(f.outcome())
                        && !"NOT_APPLICABLE".equals(f.outcome()))
                .map(f -> f.rule() + "=" + f.outcome())
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private Verdict verdictFor(Path statement) {
        String name = statement.getFileName().toString();
        try {
            byte[] content = Files.readAllBytes(statement);
            // Exactly what ImportJobWorker.stage() does for a PDF, then exactly what it feeds to
            // the entity. Any divergence here would make the whole instrument a fiction.
            StagedForJob staged = StagedForJob.of(importService.parseAndStagePdfWithSession(
                    user().getId(), name, content, null));
            VerificationTelemetry telemetry = VerificationTelemetry.from(staged.verificationReports());

            // The same call ImportJobWorker makes on its success path, over the same inputs. Not an
            // approximation of the gate -- it IS the gate, so this row says whether this statement
            // would reach the user's ledger or be quarantined.
            HoldDecision decision = TrustPredicate.evaluate(staged.verificationReports(),
                    staged.statementPeriods(), LocalDate.now(java.time.ZoneOffset.UTC));

            return new Verdict(name, telemetry.reliabilityStatus(), telemetry.textSource(),
                    telemetry.headerReconstructionUncertain(), telemetry.findingsCount(),
                    telemetry.failedCount(), telemetry.warningCount(), staged.stagedRows(), null,
                    decision.hold(), decision.summary(), notableRulesOf(staged));
        } catch (Exception e) {
            String why = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage().replaceAll("\\s+", " "));
            return new Verdict(name, null, null, false, 0, 0, 0, 0,
                    why.length() > 120 ? why.substring(0, 120) + "..." : why, false, "", "");
        }
    }

    @Test
    void everyRealStatementProducesATrustVerdict() throws Exception {
        Path corpus = corpusDir();
        assumeTrue(corpus != null && Files.isDirectory(corpus),
                "FINORA_CORPUS_DIR is not set to a real corpus directory -- this instrument only "
                        + "runs locally, because the corpus can never be committed");

        List<Path> statements = statements(corpus);
        assumeTrue(!statements.isEmpty(), "no statements found under " + corpus);

        List<Verdict> verdicts = new ArrayList<>();
        for (Path statement : statements) {
            verdicts.add(verdictFor(statement));
        }

        System.out.print(report(verdicts));

        // The one thing that can genuinely fail here, and the reason this is a test rather than a
        // script: a statement that staged rows but produced no verification report is a document
        // the trust model is blind to. Gating on a signal that is absent for real statements would
        // silently pass exactly the documents least likely to deserve it.
        List<Verdict> blind = verdicts.stream()
                .filter(v -> v.error() == null && v.stagedRows() > 0 && v.status() == null)
                .toList();
        assertThat(blind)
                .as("statements that staged rows but produced no verification report -- the trust "
                        + "model cannot see these at all")
                .isEmpty();
    }

    /** The distribution, plus the per-file detail needed to go and look at an outlier. */
    private static String report(List<Verdict> verdicts) {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (ImportReliabilityStatus status : ImportReliabilityStatus.values()) {
            byStatus.put(status.name(), 0);
        }
        byStatus.put("NO_REPORT", 0);
        byStatus.put("ERROR", 0);

        Map<String, Integer> bySource = new LinkedHashMap<>();
        int headerUncertain = 0;
        int withFailed = 0;
        int withWarning = 0;
        for (Verdict v : verdicts) {
            byStatus.merge(v.display(), 1, Integer::sum);
            if (v.textSource() != null) bySource.merge(v.textSource(), 1, Integer::sum);
            if (v.headerUncertain()) headerUncertain++;
            if (v.failed() > 0) withFailed++;
            if (v.warning() > 0) withWarning++;
        }

        StringBuilder out = new StringBuilder("\n\nTRUST TELEMETRY OVER THE REAL CORPUS\n");
        out.append("=".repeat(104)).append('\n');
        out.append(String.format("%-44s %-20s %-10s %6s %7s %8s%n",
                "statement", "verdict", "source", "rows", "failed", "warning"));
        out.append("-".repeat(104)).append('\n');
        for (Verdict v : verdicts) {
            out.append(String.format("%-44s %-20s %-10s %6d %7d %8s%n",
                    v.file().length() > 43 ? v.file().substring(0, 40) + "..." : v.file(),
                    v.display(),
                    v.textSource() == null ? "-" : v.textSource(),
                    v.stagedRows(), v.failed(),
                    v.headerUncertain() ? v.warning() + " H" : String.valueOf(v.warning())));
        }
        out.append("-".repeat(104)).append('\n');

        // A document that threw has no trust verdict at all, and "ERROR" on its own is not a
        // usable finding -- the reason is the whole point, since a parse failure is a different
        // problem from a trust signal and gets fixed somewhere else entirely.
        List<Verdict> errored = verdicts.stream().filter(v -> v.error() != null).toList();
        if (!errored.isEmpty()) {
            out.append("did not parse -- no trust verdict possible:\n");
            for (Verdict v : errored) {
                out.append(String.format("  %-42s %s%n", v.file(), v.error()));
            }
            out.append("-".repeat(104)).append('\n');
        }

        // What the shipped gate would actually do to these documents. Distinct from the verdict
        // distribution above on purpose: the aggregate ImportReliabilityStatus is NOT the gate, and
        // seeing the two side by side is what makes that concrete rather than a claim in a comment.
        List<Verdict> wouldHold = verdicts.stream().filter(Verdict::held).toList();
        List<Verdict> parsed = verdicts.stream().filter(v -> v.error() == null).toList();
        out.append("would be HELD by TrustPredicate: ").append(wouldHold.size())
                .append(" of ").append(parsed.size()).append(" that parsed\n");
        for (Verdict v : wouldHold) {
            out.append(String.format("  %-42s %s%n", v.file(), v.holdReasons()));
        }

        // The documents where the aggregate verdict and the gate disagree. This is the interesting
        // list, not the held one: every row here is a statement something flagged and the gate
        // deliberately let through, and reading the rule names is how you check that each is an
        // excluded-by-design signal rather than a condition that quietly stopped working.
        List<Verdict> flaggedButReleased = parsed.stream()
                .filter(v -> !v.held() && !v.notableRules().isBlank())
                .toList();
        if (!flaggedButReleased.isEmpty()) {
            out.append("flagged but NOT held (the aggregate status is not the gate):\n");
            for (Verdict v : flaggedButReleased) {
                out.append(String.format("  %-42s %-20s %s%n",
                        v.file(), v.display(), v.notableRules()));
            }
        }
        out.append("-".repeat(104)).append('\n');

        out.append("documents: ").append(verdicts.size()).append('\n');
        out.append("verdicts:  ").append(byStatus).append('\n');
        out.append("source:    ").append(bySource).append('\n');
        out.append(String.format("header reconstruction uncertain: %d   any FAILED rule: %d   "
                + "any WARNING rule: %d%n", headerUncertain, withFailed, withWarning));
        out.append("""

                Read this as a firing rate, not a threshold. NEEDS_ATTENTION is what a gate would
                hold today; whether that rate is tolerable is a product decision, and this corpus
                is a handful of banks rather than a representative sample of real uploads.
                """);
        out.append("=".repeat(104)).append("\n\n");
        return out.toString();
    }
}
