-- "Ask Once, Learn Forever": rather than silently filing a low-confidence transaction under
-- "Other" and hoping the user notices, it gets flagged here. Once the user picks a real
-- category (via PATCH /transactions/{id}/category), the flag clears and that merchant is
-- learned — the whole point is the user is asked exactly once per merchant, never again.
ALTER TABLE transactions ADD COLUMN needs_category_review BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX idx_transactions_needs_review ON transactions(user_id) WHERE needs_category_review = true AND deleted_at IS NULL;
