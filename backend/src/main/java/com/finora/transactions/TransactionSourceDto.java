package com.finora.transactions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "Where did this number come from?" -- Track C/C7, the user-scoped counterpart to the
 * admin-only Import Row Trace ({@code AdminImportRowTraceController}). Reads data the import
 * pipeline already recorded on the transaction itself ({@code Transaction.statementImportId} /
 * {@code sourceRowPosition}) rather than computing anything new -- same "thin, presentation-only
 * read" shape as {@link TransactionExplanationDto}.
 *
 * <p>{@code available} is {@code false} (with every other field {@code null} but {@code
 * sourceLabel}) for a transaction that was never imported from a statement row: a manual entry, a
 * Gmail receipt, or one imported before {@code sourceRowPosition} existed. That is a real,
 * statable answer -- "not from a bank statement" -- not an error, mirroring {@code
 * ImportRowTraceDto}'s own doc comment on the same point.
 */
public record TransactionSourceDto(
        boolean available,
        String sourceLabel,
        UUID statementImportId,
        String fileName,
        Integer rowPosition,
        Instant importedAt,
        String accountName,
        LocalDate statementPeriodStart,
        LocalDate statementPeriodEnd
) {
    public static TransactionSourceDto notAvailable(String sourceLabel) {
        return new TransactionSourceDto(false, sourceLabel, null, null, null, null, null, null, null);
    }
}
