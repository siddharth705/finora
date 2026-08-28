-- Reconciliation Phase 2 (docs/proposals/reconciliation-evolution-roadmap-proposal.md, Part 5):
-- source_trust travels alongside confidence (match_confidence, the per-match score already added
-- in V114) as its own persisted field rather than a value recomputed later -- SourceTrust.of() is
-- a static, per-source constant today, but a historical edge's recorded trust must stay whatever
-- it was AT MATCH TIME even if that constant is retuned in the future. The two scores are
-- deliberately never blended into one number (see the roadmap doc's "two independent scores, not
-- one blend" section) -- this column exists precisely so nothing downstream has to re-derive
-- source_trust from the transaction's current source and risk it silently drifting from what a
-- past classification actually used.
ALTER TABLE transaction_relationships ADD COLUMN source_trust INTEGER;
