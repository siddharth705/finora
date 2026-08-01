-- Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
-- both best-effort and nullable, populated only when the source statement actually carried a
-- recognizable reference/cheque/instrument-ID column or a running-balance column -- never
-- guessed. See TransactionNormalizer.REFERENCE_HINTS/BALANCE_HINTS and StagedRow.
ALTER TABLE transactions ADD COLUMN reference_number VARCHAR(64);
ALTER TABLE transactions ADD COLUMN balance_after NUMERIC(15,2);
