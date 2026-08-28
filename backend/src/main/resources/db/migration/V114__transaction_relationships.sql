-- Reconciliation Phase 2 (docs/proposals/reconciliation-evolution-roadmap-proposal.md, Part 3):
-- a many-to-many transaction graph, additive alongside the existing single-pointer legacy columns
-- (is_duplicate_of, transfer_pair_id, refund_of_transaction_id) rather than replacing them. Those
-- columns can express at most one relationship per transaction; a credit card payment settling 14
-- separate spends, for example, needs 14 rows here, one edge each. from/to are plain UUID columns,
-- not foreign keys -- Transaction never declares FKs against itself elsewhere in this schema either
-- (see is_duplicate_of/transfer_pair_id/refund_of_transaction_id, none of which is one), and a
-- relationship must survive a soft-deleted transaction on either side rather than being blocked by
-- one, since Transaction uses @SQLDelete rather than a real DELETE.
CREATE TABLE transaction_relationships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    from_transaction_id UUID NOT NULL,
    to_transaction_id UUID NOT NULL,
    relationship_type VARCHAR(30) NOT NULL,
    matched_amount NUMERIC(14,2),
    confidence INTEGER,
    status VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE',
    detection_method VARCHAR(20) NOT NULL,
    explanation JSONB,
    superseded_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- user_id is denormalized off both transactions rather than joined for it: every read path this
-- graph serves (getGraph(transactionId), and any future per-user relationship listing) is scoped
-- to one user first, same reasoning as Transaction.userId itself being a plain column rather than
-- derived through account_id.
CREATE INDEX idx_transaction_relationships_user_id ON transaction_relationships(user_id);

-- The graph is walked from either endpoint (getGraph(id) has to find edges where id is the "from"
-- OR the "to" side -- a transfer pair, for instance, is symmetric), so both directions need their
-- own index; a single composite index on (from_transaction_id, to_transaction_id) would not serve
-- a lookup that starts from to_transaction_id without a full scan.
CREATE INDEX idx_transaction_relationships_from ON transaction_relationships(from_transaction_id);
CREATE INDEX idx_transaction_relationships_to ON transaction_relationships(to_transaction_id);

-- One-time backfill: materialize an edge for every relationship the legacy columns already
-- record, so the graph is complete from day one rather than only covering pairs matched after
-- this migration runs. Pure copy, not re-derivation -- confidence 100 (the scale
-- Transaction.decisionConfidence already uses) and RULE_ENGINE/AUTO_CONFIRMED throughout, since
-- every one of these rows was already treated as an authoritative classification by
-- ReconciliationService, not a candidate awaiting review. Safe to run unconditionally: this
-- migration creates the table in the same transaction, so there is no way for a duplicate edge to
-- already exist.
--
-- Duplicates: one edge per is_duplicate_of pointer.
INSERT INTO transaction_relationships
    (user_id, from_transaction_id, to_transaction_id, relationship_type, matched_amount, confidence, status, detection_method)
SELECT user_id, id, is_duplicate_of, 'DUPLICATE', amount, 100, 'AUTO_CONFIRMED', 'RULE_ENGINE'
FROM transactions
WHERE is_duplicate_of IS NOT NULL AND deleted_at IS NULL;

-- Transfers: transfer_pair_id is written symmetrically on both sides of a pair (see
-- ReconciliationService's transfer pass), so this naturally produces one edge per direction --
-- two edges per pair, matching the legacy column's own shape rather than picking an arbitrary
-- canonical direction.
INSERT INTO transaction_relationships
    (user_id, from_transaction_id, to_transaction_id, relationship_type, matched_amount, confidence, status, detection_method)
SELECT user_id, id, transfer_pair_id, 'TRANSFER', amount, 100, 'AUTO_CONFIRMED', 'RULE_ENGINE'
FROM transactions
WHERE transfer_pair_id IS NOT NULL AND deleted_at IS NULL;

-- Refunds and reversals share refund_of_transaction_id (see that column's own comment on
-- Transaction) -- reconciliation_status tells them apart.
INSERT INTO transaction_relationships
    (user_id, from_transaction_id, to_transaction_id, relationship_type, matched_amount, confidence, status, detection_method)
SELECT user_id, id, refund_of_transaction_id, reconciliation_status, amount, 100, 'AUTO_CONFIRMED', 'RULE_ENGINE'
FROM transactions
WHERE refund_of_transaction_id IS NOT NULL AND deleted_at IS NULL
  AND reconciliation_status IN ('REFUND', 'REVERSAL');
