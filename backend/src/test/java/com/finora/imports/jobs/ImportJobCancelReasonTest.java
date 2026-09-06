package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a user is told when they press Cancel too late.
 *
 * <p>This was a {@code default ->} arm until the trust hold arrived, and that arm has already been
 * wrong once: {@code HELD_FOR_REVIEW} fell into it and told users their import "is already writing
 * to your accounts", which was untrue -- a held job never reached IMPORTING and wrote nothing. The
 * fix at the time was to give that one status its own case, which left the same trap armed for the
 * next status added. {@code HELD_FOR_TRUST_REVIEW} would have walked straight into it.
 *
 * <p>So the switch is now exhaustive with no default, and the compiler -- not a reviewer noticing
 * -- is what forces the next status to be considered. These tests pin the two properties that
 * makes worth having: every status produces an answer, and no state that wrote nothing to the
 * ledger is described as having written to it.
 */
class ImportJobCancelReasonTest {

    /** A trust-held job staged rows but confirmed none of them; "writing to your accounts" is a lie. */
    @Test
    void aTrustHeldJobIsNotDescribedAsWritingToTheLedger() {
        String reason = ImportJobService.uncancellableReason(ImportJob.Status.HELD_FOR_TRUST_REVIEW);

        assertThat(reason).doesNotContain("writing to your accounts");
    }

    /** Both holds read identically to the user, matching the collapse in UserFacingImportStatus. */
    @Test
    void bothHoldsGiveTheUserTheSameAnswer() {
        assertThat(ImportJobService.uncancellableReason(ImportJob.Status.HELD_FOR_TRUST_REVIEW))
                .isEqualTo(ImportJobService.uncancellableReason(ImportJob.Status.HELD_FOR_REVIEW));
    }

    /**
     * The copy rule that governs every user-visible string in this feature: never suggest the
     * statement itself is in question. For a trust hold the doubt is about our own extraction, so
     * this is the message most at risk of leaking it.
     */
    @Test
    void theTrustHoldMessageDoesNotImpugnTheStatement() {
        String reason = ImportJobService.uncancellableReason(ImportJob.Status.HELD_FOR_TRUST_REVIEW)
                .toLowerCase();

        assertThat(reason).doesNotContain("verify", "genuine", "authentic", "suspicious", "fraud");
    }

    /** No status may fall through to null or blank -- the exhaustive switch is only worth having
     *  if every arm actually answers. */
    @ParameterizedTest
    @EnumSource(ImportJob.Status.class)
    void everyStatusHasAnAnswer(ImportJob.Status status) {
        assertThat(ImportJobService.uncancellableReason(status)).isNotBlank();
    }

    /**
     * Only the states that genuinely reached the ledger may say so. IMPORTING and LEARNING are the
     * two that have written transactions; every other status has not.
     */
    @ParameterizedTest
    @EnumSource(value = ImportJob.Status.class,
            names = {"IMPORTING", "LEARNING"}, mode = EnumSource.Mode.EXCLUDE)
    void noStatusOutsideTheWritingStagesClaimsToHaveWritten(ImportJob.Status status) {
        assertThat(ImportJobService.uncancellableReason(status))
                .as("%s has not written to the ledger and must not say it has", status)
                .doesNotContain("writing to your accounts");
    }
}
