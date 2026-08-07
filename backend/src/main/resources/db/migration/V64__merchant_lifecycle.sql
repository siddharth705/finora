-- WI4 of the Import Reliability Milestone: the temporary merchant workflow
-- (docs/engineering/import-reliability-milestone-design.md).
--
-- An unknown merchant must never block an import. It already does not -- MerchantNormalizationEngine
-- always resolves to SOMETHING -- but until now the engine's guesses were indistinguishable from
-- merchants a human had actually approved. That is Bug 36's real cost: staging a statement and
-- abandoning it left permanent merchant rows nobody asked for, counted in the user's Merchants
-- page and in the admin's platform-wide totals, with no way to tell them apart from real ones.
--
-- This column is what makes them distinguishable, and it is the prerequisite for WI3: staging can
-- only stop persisting once unknown merchants have somewhere else to go.

ALTER TABLE merchants
    ADD COLUMN lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'APPROVED';

-- Existing rows are APPROVED, deliberately and not merely for convenience: every merchant that
-- exists today came from a CONFIRMED import or an explicit admin action, so none of them are
-- provisional. Backfilling them to TEMPORARY would drop the entire existing merchant table into
-- an operator's review queue on the first deploy.
COMMENT ON COLUMN merchants.lifecycle_status IS
    'TEMPORARY (created automatically by the normalization engine, awaiting review), '
    'UNDER_REVIEW (an operator has picked it up), or APPROVED (confirmed, or pre-dating V64). '
    'See docs/engineering/import-reliability-milestone-design.md WI4.';

-- The Review Center''s primary query: "what needs review", per user. Partial, because APPROVED is
-- the overwhelming majority and is never what the queue asks for -- a full index would be mostly
-- rows nobody queries by this column.
CREATE INDEX idx_merchants_needs_review
    ON merchants (user_id, lifecycle_status)
    WHERE lifecycle_status <> 'APPROVED';

-- ---------------------------------------------------------------------------------------------
-- MERCHANT_REVIEW.
--
-- Separate from MERCHANT_MANAGE, which already exists and gates the per-user merchant
-- administration surface (rename, merge, confirm-category, undo, reset-learning). That permission
-- is about curating ONE user's merchants on their behalf, usually while helping them with
-- something. This one is about working a cross-user queue of the engine's own guesses, which is an
-- operational duty rather than a support one, and the two are not the same job.
--
-- Same reasoning V61 applied for ENGINE_ANALYSIS_RUN and V63 for LEARNING_QUEUE_MANAGE: a new
-- capability gets its own grant rather than being folded into the nearest existing one, so it can
-- be given to the people who need it and withheld from the people who do not.
INSERT INTO permissions (name, description) VALUES
    ('MERCHANT_REVIEW',
     'Review merchants the import engine created automatically: approve, rename, merge or discard '
     'them. Scoped to the owning user for every action.');

-- ADMIN and SUPER_ADMIN, matching every permission added since V24. SUPER_ADMIN needs its own
-- explicit grant for the reason documented on V24/V25/V28/V29/V30/V34/V61/V63: its V16 "every
-- permission" catch-all was a one-time snapshot, not a standing rule.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'MERCHANT_REVIEW';
