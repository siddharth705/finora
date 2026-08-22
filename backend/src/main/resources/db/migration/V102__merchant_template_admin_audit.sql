-- Admin UI for merchant_templates. Every template today was added via a Flyway migration
-- (V85/V86) with no admin API at all -- this is the first of several changes that add one. Adding
-- a template stays a real, audit-worthy action even though it is not the trust boundary (that is
-- gmail_trusted_sender_domains, whose added_by_user_id this mirrors exactly): a bad amount/date
-- pattern still mis-stages a wrong amount into a real user's ledger, so "who added this, and when"
-- must stay answerable here too.
--
-- No backfill: V85/V86's own seeded rows (Uber, Zomato) predate any admin actor and stay NULL,
-- same as gmail_trusted_sender_domains leaves added_by_user_id NULL for its own migration-seeded
-- rows.
ALTER TABLE merchant_templates
    ADD COLUMN created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL;
