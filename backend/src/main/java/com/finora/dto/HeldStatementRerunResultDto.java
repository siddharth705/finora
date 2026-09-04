package com.finora.dto;

import java.util.List;

/**
 * What one parser re-run found: the two parser versions being compared, whether they differ, and
 * the fresh trust decision.
 *
 * @param previousParserVersion the build that produced the rows still staged for this hold --
 *                              {@code HeldStatement.parserVersion}, unchanged by this call.
 * @param currentParserVersion the build running right now, read from {@code
 *                             ParserVersionProvider} -- the same source {@code
 *                             HeldStatement.parserVersion} was originally stamped from via {@code
 *                             ImportJobWorker}. Null if neither {@code app.parser-version} nor
 *                             {@code RAILWAY_GIT_COMMIT_SHA} is set.
 * @param parserVersionChanged whether the two differ. False does not mean the re-run is pointless
 *                             -- a config or dependency change with no commit bump is possible,
 *                             if rare -- so the UI shows this as a hint, not a gate.
 * @param stillHeld whether {@code TrustPredicate} still flags this statement under the current
 *                  build.
 * @param reasons why it still holds, if it does -- empty when {@code stillHeld} is false.
 * @param summary the hold's current state after this call -- {@code READY_FOR_IMPORT} if this
 *                run cleared it, unchanged otherwise.
 */
public record HeldStatementRerunResultDto(
        String previousParserVersion,
        String currentParserVersion,
        boolean parserVersionChanged,
        boolean stillHeld,
        List<String> reasons,
        HeldStatementDto summary) {}
