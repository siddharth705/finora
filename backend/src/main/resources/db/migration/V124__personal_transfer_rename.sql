-- Renames V123's "Paid a Person" to the direction-neutral "Personal Transfer".
--
-- V123 named the category for what it assumed the detector matched: money going OUT to an
-- individual. PersonToPersonTransferDetector has never inspected direction -- it matches on
-- narration shape alone -- so on the real 29-statement corpus 99 of the 434 rows it classified
-- (22.8% of them, 20.9% by value) are money RECEIVED, described by a label that says they were
-- paid. The previous "Transfer" label hid this because it is directionless.
--
-- Direction is already a column on the transaction (txn_type). Encoding it a second time in the
-- category NAME only creates a second place for it to disagree with the first. A UI that wants to
-- say "Sent to a person" or "Received from a person" composes those two facts; the stored label
-- states only what the detector established, which is that a person was on the other side.
--
-- Renaming the category row is the whole migration: every transaction already points at it by id,
-- so all of them inherit the corrected label with no transactions table write at all.

-- is_system only. A category a user created by hand with this name is theirs, and V123 explicitly
-- left such rows alone rather than absorbing them; renaming one here would take that back.
--
-- The NOT EXISTS guard is not defensive padding: categories carries uq_categories_user_name_ci
-- (V118), so for any user who already has a "Personal Transfer" row this rename would violate it
-- and abort the migration -- which does not degrade, it stops the backend booting. Such a user
-- keeps their V123 row under the old name; CategorizationService.resolveOrCreateCategory looks up
-- by name case-insensitively and will simply resolve future detections to the row they already
-- have, so nothing is orphaned.
UPDATE categories c
SET name = 'Personal Transfer'
WHERE c.is_system
  AND lower(c.name) = 'paid a person'
  AND NOT EXISTS (
      SELECT 1 FROM categories x
      WHERE x.user_id = c.user_id AND lower(x.name) = 'personal transfer'
  );
