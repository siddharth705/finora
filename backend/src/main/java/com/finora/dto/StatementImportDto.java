package com.finora.dto;

import com.finora.accounts.AccountDto;
import com.finora.entity.StatementImport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class StatementImportDto {

    /** duplicateCount: how many of this import's own transactions are currently flagged
     *  ReconciliationStatus.DUPLICATE -- computed on read from existing transaction data
     *  (StatementImportService), not stored on StatementImport itself. Financial Intelligence
     *  Workspace, Statement Imports module: "Processing Time" and "Import Logs" were considered
     *  for this same module and deliberately descoped -- neither a start/end processing timestamp
     *  nor a per-row import log is captured anywhere today, and adding that storage is new
     *  intelligence/schema, not the "visualize what already exists" reuse this phase is scoped to
     *  (see docs/team-message-financial-intelligence-workspace-kickoff.md's non-goals). Duplicate
     *  count had no such gap -- ReconciliationStatus already exists on every transaction. */
    public record Summary(
            UUID id,
            String fileName,
            LocalDate statementPeriodStart,
            LocalDate statementPeriodEnd,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            int transactionsImported,
            int transactionsSkipped,
            String status,
            Instant importedAt,
            int duplicateCount
    ) {
        public static Summary from(StatementImport s, int duplicateCount) {
            return new Summary(s.getId(), s.getFileName(), s.getStatementPeriodStart(), s.getStatementPeriodEnd(),
                    s.getOpeningBalance(), s.getClosingBalance(), s.getTransactionsImported(),
                    s.getTransactionsSkipped(), s.getStatus(), s.getImportedAt(), duplicateCount);
        }
    }

    /** One entry per account that has ever had a statement imported — accounts with no import
     *  history simply don't appear, rather than showing an empty group.
     *
     *  deleted/deletedAt cover the "account was deleted after a statement was imported into it"
     *  case (see StatementImportService.listGroupedByAccount): that history still deserves to
     *  show up, but only for a 7-day grace period after deletion — after that the group is
     *  dropped from the response entirely rather than lingering forever. */
    public record AccountGroup(
            UUID accountId, String accountName, String accountType, AccountDto.BankDto bank,
            List<Summary> statements, boolean deleted, Instant deletedAt
    ) {}

    /** Result of "Re-import Statement": replays the originally-stored file back through the same
     *  staging pipeline as a fresh upload, but already scoped to the account it was imported into
     *  (no "create new account" choice needed — the account already exists). */
    public record ReimportResult(ImportDto.StagingResponse staging, UUID accountId, String accountName) {}

    /**
     * Optional body for "Re-import Statement". Exists only to carry the document password for a
     * password-protected PDF: re-import re-parses the ORIGINAL stored bytes, and those bytes are
     * still encrypted -- the password used at upload time is deliberately never persisted (see
     * PdfPreviewGenerator's password parameter), so it has to be supplied again here.
     *
     * The whole body is optional, and so is the field, so an existing client that posts nothing at
     * all still works exactly as before. Deliberately a body rather than a query parameter: a
     * document password in a URL would be captured by access logs, proxy logs and browser history.
     */
    public record ReimportRequest(String password) {}
}
