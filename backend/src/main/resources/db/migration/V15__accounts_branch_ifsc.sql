-- Optional branch/IFSC metadata (PRD's "Rich Account Information"). Both nullable -- most
-- existing accounts (manually created, or imported from a statement without a branch/IFSC
-- column) genuinely have no value for either, and there's nothing to backfill from at migration
-- time, so this stays honestly null rather than defaulted to a fabricated placeholder.
ALTER TABLE accounts ADD COLUMN branch_name VARCHAR(120);
ALTER TABLE accounts ADD COLUMN ifsc_code VARCHAR(11);
