-- Credit card statement entity, roadmap item 6 (docs/proposals/credit-card-statement-entity-design.md,
-- revised mid-implementation once PdfMetadataExtractor was found to already extract payment due
-- date). No new table: both fields already reach DetectedAccountInfo at staging time and only
-- need a place to land at confirm -- statement_imports already carries the analogous
-- opening/closing balance and period columns for the same "printed on the statement" data.
ALTER TABLE statement_imports ADD COLUMN total_amount_due NUMERIC(14,2);
ALTER TABLE statement_imports ADD COLUMN payment_due_date DATE;
