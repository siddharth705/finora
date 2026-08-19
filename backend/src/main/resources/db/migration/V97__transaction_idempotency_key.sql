-- SEC-06 (docs/quality/bug-reports/2026-08-19-security-review-findings.md): POST /transactions had
-- no idempotency protection at all -- a double-click or a client retrying a request whose response
-- was lost creates two Transaction rows, and TransactionService.create() moves the account's real
-- balance on every call regardless. ReconciliationService can flag the second row DUPLICATE, but a
-- DUPLICATE-flagged row still counts toward Account.balance (see confirmNotDuplicate's own doc
-- comment) -- only reports/dashboards exclude it. The import pipeline already has two versions of
-- this exact protection (V74 for import_jobs, V79 for import_sessions); this closes the same gap on
-- the one write path that never got either.
--
-- WHY A CLIENT-SUPPLIED KEY, NOT A CONTENT HASH LIKE V74/V79
-- ------------------------------------------------------------
-- V74 and V79 dedupe on a hash of the uploaded document's bytes, which is correct there because the
-- same bytes uploaded twice really is the same event. A manual transaction has no equivalent: two
-- entries with an identical amount, date, description and account are not necessarily the same
-- event -- two identical coffees on the same day is a completely ordinary, legitimate case (see
-- Transaction.notDuplicateConfirmedAt, built for exactly this). Hashing the transaction's own fields
-- would silently reject the second, real transaction. The standard fix is a client-generated
-- identifier per logical create attempt (a UUID minted once by the client, resent unchanged on any
-- retry of THAT SAME attempt) -- unrelated to the transaction's content, so two genuinely different
-- transactions never collide, and a retried request always does.
--
-- WHY A PLAIN UNIQUE INDEX, NOT A PARTIAL ONE SCOPED TO "LIVE" ROWS LIKE V74/V79
-- --------------------------------------------------------------------------------
-- V74/V79 exclude terminal/soft-deleted states because re-submitting the SAME document after an
-- earlier attempt finished, failed or was cancelled is a legitimate new event that must be allowed
-- to succeed. An idempotency key has the opposite contract: it identifies one logical request, and
-- replaying that request is defined to return the ORIGINAL result, forever -- even if that
-- transaction is later edited or soft-deleted, the key must keep resolving to the same identity
-- rather than quietly permitting a second insert under it. Nullable and scoped to non-null values
-- only, since older clients and every other write path (CSV/PDF/Gmail import, edits) never set this
-- column at all.
ALTER TABLE transactions ADD COLUMN idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX idx_transactions_user_idempotency_key
    ON transactions (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN transactions.idempotency_key IS
    'Client-generated identifier for one logical POST /transactions attempt. Set only by the '
    'manual create path (TransactionService.create) -- null for every CSV/PDF/Gmail-imported row.';
