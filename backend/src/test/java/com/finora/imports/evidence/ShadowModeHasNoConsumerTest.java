package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The compile-time half of "observe, don't control" -- the integration mapping's own acceptance
 * criterion for shadow mode: the assessment must have <b>no consumer</b> outside the evidence
 * package.
 *
 * <p>{@link ClosingBalanceEvidenceShadowObserver#observe} returning {@code void} already makes it
 * impossible to branch on the result at the seam. This test guards the wider property that nobody
 * reaches around it -- no production class outside {@code com.finora.imports.evidence} names a
 * {@link FieldAssessment}, an {@link EvidenceStatus} or the re-derivation service, so no
 * {@code if (status == ...)} can exist anywhere in the import pipeline. When enforcement is
 * eventually designed (ADR-006 §5), this test is what has to be deliberately changed, which is the
 * point: the transition from observation to control becomes a visible decision rather than a diff
 * nobody noticed.
 */
class ShadowModeHasNoConsumerTest {

    private static final Pattern EVIDENCE_TYPES = Pattern.compile(
            "FieldAssessment|EvidenceStatus|EvidenceComparison|DimensionResult|FieldCandidate"
                    + "|MetadataEvidencePipeline|ClosingBalanceEvidenceRederivationService");

    @Test
    void noProductionClassOutsideTheEvidencePackageReadsAnAssessment() throws IOException {
        Path main = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(main)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("com/finora/imports/evidence/"))
                    .filter(p -> containsEvidenceType(p))
                    .map(Path::toString)
                    .toList();

            assertThat(offenders)
                    .as("shadow mode is observation only -- an evidence verdict read outside the "
                            + "evidence package is enforcement, whether or not it was meant to be")
                    .isEmpty();
        }
    }

    private static boolean containsEvidenceType(Path file) {
        try {
            String source = Files.readString(file);
            // The observer's own TYPE may be named (ImportService injects it and calls its void
            // method); what may not appear is anything that carries or is a verdict.
            return EVIDENCE_TYPES.matcher(source).find();
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file, e);
        }
    }
}
