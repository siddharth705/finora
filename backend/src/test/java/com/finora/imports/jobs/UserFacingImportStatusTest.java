package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Premium Import Reliability v1, §1, Sprint 4 item 20a. Standalone from {@link ImportJobDto} on
 * purpose, same reasoning as {@link ExceptionClassifierTest}: a pure function, provable in
 * isolation before anything wires it into a wire response.
 */
class UserFacingImportStatusTest {

    @Test
    void everyInFlightStatus_mapsToProcessing() {
        for (ImportJob.Status status : ImportJob.Status.IN_FLIGHT) {
            assertThat(UserFacingImportStatus.of(status, null))
                    .as("status %s", status)
                    .isEqualTo(UserFacingImportStatus.PROCESSING);
        }
    }

    @Test
    void queued_alsoMapsToProcessing() {
        // Not itself IN_FLIGHT (that set is specifically the states a worker holds a job in, per
        // ImportJob's own doc) but still something the user should see as "working on it".
        assertThat(UserFacingImportStatus.of(ImportJob.Status.QUEUED, null))
                .isEqualTo(UserFacingImportStatus.PROCESSING);
    }

    @Test
    void completed_mapsToCompleted() {
        assertThat(UserFacingImportStatus.of(ImportJob.Status.COMPLETED, null))
                .isEqualTo(UserFacingImportStatus.COMPLETED);
    }

    @Test
    void cancelled_mapsToCancelled() {
        assertThat(UserFacingImportStatus.of(ImportJob.Status.CANCELLED, null))
                .isEqualTo(UserFacingImportStatus.CANCELLED);
    }

    @Test
    void failedWithAnActionableCode_mapsToActionRequired() {
        assertThat(UserFacingImportStatus.of(ImportJob.Status.FAILED, "IMPORT_PDF_PASSWORD_REQUIRED"))
                .isEqualTo(UserFacingImportStatus.ACTION_REQUIRED);
    }

    @Test
    void failedWithANonActionableCode_mapsToFailed() {
        assertThat(UserFacingImportStatus.of(ImportJob.Status.FAILED, "IMPORT_CORRUPT_PDF"))
                .isEqualTo(UserFacingImportStatus.FAILED);
    }

    @Test
    void failedWithNoCuratedCodeAtAll_mapsToFailed() {
        // A tier-3 dead-letter carrying only an exception's simple class name (ErrorCode.failureCodeOf's
        // fallback) -- there is no known fix to offer, so this is plain FAILED, not a thrown exception.
        assertThat(UserFacingImportStatus.of(ImportJob.Status.FAILED, "NullPointerException"))
                .isEqualTo(UserFacingImportStatus.FAILED);
    }

    @Test
    void failedWithNoFailureCodeRecordedAtAll_mapsToFailed() {
        assertThat(UserFacingImportStatus.of(ImportJob.Status.FAILED, null))
                .isEqualTo(UserFacingImportStatus.FAILED);
    }

    @Test
    void aNonFailedStatus_ignoresFailureCode() {
        // failureCode is only consulted for FAILED -- a stray/stale value on any other status must
        // not leak into the mapping, matching every other reader of this field's own gating rule.
        assertThat(UserFacingImportStatus.of(ImportJob.Status.COMPLETED, "IMPORT_PDF_PASSWORD_REQUIRED"))
                .isEqualTo(UserFacingImportStatus.COMPLETED);
    }
}
