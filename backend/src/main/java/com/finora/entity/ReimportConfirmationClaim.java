package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per confirmed re-import attempt, written by
 * {@code StatementImportService.confirmReimport} before it does any importing.
 *
 * <p>This exists because the re-import path had no equivalent of the first-time-import path's
 * protection. {@code ImportService.confirmSession} claims its {@link ImportSession} atomically as
 * its very first act, so two concurrent confirms of one staged import cannot both proceed. A
 * re-import has no session to claim — it replays bytes already stored on a {@code StatementImport}
 * — so it went from an ownership check straight to persisting, and a double-tapped "Re-import" (or
 * any client retry of a confirm whose response was lost) posted the statement's transactions twice.
 *
 * <p><b>The unique index is the guarantee, not the lookup.</b> A SELECT-then-INSERT would still let
 * two concurrent requests both read "no claim yet"; what actually serializes them is
 * {@code idx_reimport_claims_user_idempotency_key}. The second INSERT blocks until the first
 * commits and then fails, and since the claim shares a transaction with the import it guards, that
 * failure unwinds an import which has written nothing the user can see.
 *
 * <p>Deliberately not unique on {@code statementImportId}: re-importing the same statement again
 * later is a legitimate, repeatable action — that is the whole point of the feature. Only a replay
 * of one <em>attempt</em>, identified by a key the client mints per attempt and resends unchanged
 * on retry, is refused.
 *
 * <p>No cleanup job, matching {@link ImportSession}'s own reasoning: this codebase has no
 * background-job infrastructure, and these rows are tiny (no file bytes, no financial data — a key
 * and two foreign keys). Called out explicitly rather than left as silent unbounded growth.
 */
@Entity
@Table(name = "reimport_confirmation_claims")
public class ReimportConfirmationClaim {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "statement_import_id", nullable = false)
    private UUID statementImportId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getStatementImportId() { return statementImportId; }
    public void setStatementImportId(UUID statementImportId) { this.statementImportId = statementImportId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
