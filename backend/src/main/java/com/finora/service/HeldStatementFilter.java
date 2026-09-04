package com.finora.service;

import com.finora.entity.HeldStatement;

import java.util.UUID;

/**
 * The operator queue's own filters, every field nullable meaning "no filter on this axis".
 *
 * <p>{@code status} narrows WITHIN the open queue -- it can never surface a resolved hold. Listing
 * a resolved statement was never something browsing the queue was meant to do (see {@code
 * HeldStatementService.OPEN} and {@code HeldStatementRepositoryIT.openQueueExcludesResolvedStatements}),
 * and a filter parameter is not a reason to relax that.
 *
 * @param olderThanHours "older than", not "newer than" -- an operator triaging asks what has been
 *                       waiting, not what just arrived.
 */
public record HeldStatementFilter(HeldStatement.Status status, String bankName,
                                  Integer olderThanHours, UUID assignedEngineerId) {
}
