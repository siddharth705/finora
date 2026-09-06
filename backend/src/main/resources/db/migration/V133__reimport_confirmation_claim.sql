-- Track B / B1+B2 (docs/project-management/plans/mobile-correctness-trust-roadmap.md): the
-- re-import confirm path had NO protection against being run twice, and running it twice really
-- does post the statement's transactions twice.
--
-- WHY ONLY THE RE-IMPORT PATH
-- ---------------------------
-- A first-time import is already safe: ImportController -> ImportService.confirmSession() calls
-- ImportSessionService.claimForConfirmation() as its very first act, an atomic UPDATE that lets
-- exactly one of two concurrent requests proceed and fails the other with
-- "This import has already been confirmed." A re-import has no ImportSession to claim -- it replays
-- bytes already stored on a StatementImport row -- so StatementImportService.confirmReimport()
-- went straight from an ownership check to importService.confirm(), which persists
-- unconditionally. A double-tapped "Re-import", or any client retry of a confirm whose response was
-- lost, produced a second complete set of transactions plus a second StatementImport row.
--
-- WHY A CLAIM TABLE RATHER THAN A COLUMN ON statement_imports
-- -----------------------------------------------------------
-- The StatementImport row this confirm creates is written deep inside ImportService.persistSection,
-- several layers below the entry point that knows this is a re-import. Threading a key down there
-- would touch every confirm overload -- including the first-time-import path that does not need it
-- and is already protected -- to guard one caller. A claim row inserted at the entry point, in the
-- same transaction as the work it guards, keeps the guard next to the hole it closes.
--
-- WHY A CLIENT-SUPPLIED KEY, NOT A HASH OF THE DOCUMENT (unlike V74/V79)
-- ---------------------------------------------------------------------
-- V74/V79 dedupe on a hash of the uploaded bytes, which is right there: the same bytes uploaded
-- twice is the same event. A re-import replays the SAME stored bytes every single time by
-- construction, so a content hash would make the first re-import of a statement succeed and every
-- later one fail -- and re-importing a statement again after correcting something is the entire
-- point of the feature. The key therefore identifies one logical confirm ATTEMPT (a UUID the client
-- mints when it builds the request and resends unchanged on any retry of that same attempt), not
-- the document. Same reasoning V97 sets out for POST /transactions.
--
-- WHY THE CLAIM IS INSERTED BEFORE THE WORK
-- -----------------------------------------
-- The unique index is the real guarantee, not the SELECT that precedes it: two concurrent requests
-- can both read "no claim yet". The second INSERT blocks on the index until the first commits and
-- then fails, and because the claim and the import share one transaction, that failure rolls back
-- an import that has written nothing the user can see. This is the same "claim before any
-- transaction-import work happens at all" discipline ImportSessionService.claimForConfirmation
-- already documents.
CREATE TABLE reimport_confirmation_claims (
    id                  UUID PRIMARY KEY,
    user_id             UUID        NOT NULL,
    statement_import_id UUID        NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Scoped to the user, not global: two users minting the same UUID is astronomically unlikely but a
-- cross-user collision would be one user's request failing because of another's, which is never an
-- acceptable failure mode for a key the client chooses.
CREATE UNIQUE INDEX idx_reimport_claims_user_idempotency_key
    ON reimport_confirmation_claims (user_id, idempotency_key);

-- Deliberately NOT unique on statement_import_id: re-importing the same statement again later is a
-- legitimate, repeatable action (that is what the feature is for). Only a replay of one ATTEMPT is
-- refused. Indexed for the cleanup/inspection path, not for uniqueness.
CREATE INDEX idx_reimport_claims_statement_import ON reimport_confirmation_claims (statement_import_id);

COMMENT ON TABLE reimport_confirmation_claims IS
    'One row per confirmed re-import attempt. Its unique (user_id, idempotency_key) index is what '
    'stops a double-tapped or retried re-import from posting the statement''s transactions twice; '
    'the first-time-import path is protected instead by ImportSession.claimForConfirmation.';
