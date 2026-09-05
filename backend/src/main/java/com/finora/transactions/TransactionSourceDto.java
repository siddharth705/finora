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
 * sourceLabel}/{@code statementDeleted}) for a transaction with no statement row to show. That
 * covers two genuinely different facts, which {@code statementDeleted} tells apart: a manual
 * entry, a Gmail receipt, or one imported before {@code sourceRowPosition} existed never HAD a
 * tracked row ({@code statementDeleted = false}); a row that WAS tracked but whose {@code
 * StatementImport} has since been deleted (a superseded re-upload, or account-purge cleanup) had
 * one and lost it ({@code statementDeleted = true}). Collapsing the two into one message would
 * tell the second case it "predates tracking," which is false. Both are real, statable answers --
 * not an error -- mirroring {@code ImportRowTraceDto}'s own doc comment on the same point.
 */
public record TransactionSourceDto(
        boolean available,
        String sourceLabel,
        boolean statementDeleted,
        UUID statementImportId,
        String fileName,
        Integer rowPosition,
        Instant importedAt,
        String accountName,
        LocalDate statementPeriodStart,
        LocalDate statementPeriodEnd
) {
    public static TransactionSourceDto notAvailable(String sourceLabel) {
        return new TransactionSourceDto(false, sourceLabel, false, null, null, null, null, null, null, null);
    }

    public static TransactionSourceDto statementDeleted(String sourceLabel) {
        return new TransactionSourceDto(false, sourceLabel, true, null, null, null, null, null, null, null);
    }
}
