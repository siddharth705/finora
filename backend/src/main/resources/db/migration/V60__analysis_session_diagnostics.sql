-- The numbers that decide capabilities, kept instead of discarded.
--
-- V59 recorded that an upload happened and how it ended. It did not record how WELL it went: a
-- document that yielded 2 transactions out of 2541 lines and one that yielded 569 both stored
-- outcome = 'PARSED' and were indistinguishable afterwards. The difference between those two is
-- the entire subject of parser work.
--
-- Both columns hold facts the pipeline already computed and then threw away at the end of the
-- request. Recovering them needed a throwaway probe printing to a console on one machine, which
-- is not evidence anyone else can check.
ALTER TABLE statement_analysis_sessions ADD COLUMN row_count INTEGER;

-- Reason -> count, e.g. {"NO_DATE_IN_ANCHOR_COLUMN": 97, "UNANCHORED_ROWS_ABANDONED": 12}.
--
-- A histogram, not a list of reasons, and the distinction is the point: every real statement
-- measured hits several reasons at least once, INCLUDING the ones that parse perfectly. Only the
-- proportion says where the fault is. Stored ordered by count descending so the dominant reason
-- reads first and the same parse run always serialises identically.
--
-- TEXT rather than JSONB to match layout_metadata_json and activated_capabilities_json, which are
-- already TEXT. Uniformity is worth more here than in-database querying: nothing queries INTO
-- these documents today, and one storage convention for parser JSON is easier to reason about
-- than two.
--
-- Never the unparseable rows themselves -- see UnparseableRowSummary. This table holds structure
-- and outcome; statement content has a home with its own retention story and does not belong in
-- a telemetry table.
ALTER TABLE statement_analysis_sessions ADD COLUMN unanchored_reasons_json TEXT;

COMMENT ON COLUMN statement_analysis_sessions.row_count IS
    'Transactions extracted across all sections. NULL means never measured, which is not the same as 0.';
COMMENT ON COLUMN statement_analysis_sessions.unanchored_reasons_json IS
    'Reason->count histogram of rows that could not be anchored, ordered by count descending.';
