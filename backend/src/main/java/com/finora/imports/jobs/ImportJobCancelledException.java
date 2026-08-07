package com.finora.imports.jobs;

import java.util.UUID;

/**
 * Raised when a worker finds that the user cancelled the job it is holding.
 *
 * <p>A distinct type rather than a flag or a plain exception, because the worker's catch block has
 * to tell this apart from a failure. Falling into the failure path would call
 * {@code recordFailure}, which sets the job back to {@code QUEUED} for a retry — so a cancelled job
 * would be resurrected and run again, which is the opposite of what the user asked for. Cancelling
 * is the only way a pass ends without either completing or failing.
 */
public class ImportJobCancelledException extends RuntimeException {

    public ImportJobCancelledException(UUID jobId) {
        super("Import job " + jobId + " was cancelled while a worker held it.");
    }
}
