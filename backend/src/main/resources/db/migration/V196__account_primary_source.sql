-- Which system owns this account's transactions going forward: the user's own manual upload
-- (default, unchanged behavior) or a live Account Aggregator link. See
-- docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md, "Data model".
ALTER TABLE accounts ADD COLUMN primary_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
