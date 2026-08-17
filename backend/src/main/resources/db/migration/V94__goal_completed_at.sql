-- D-25 PR3-B (Financial Journey). The "achieve your first goal" milestone needs to know WHEN a
-- goal reached its target, not just whether it currently sits there -- current_amount can still
-- move afterward (a later withdrawal, however rare via the API today, see GoalService's
-- floor-at-zero defense-in-depth), and that must not erase the fact the target was once met.
-- GoalService.markCompletedIfReached sets this exactly once, the first time current_amount
-- reaches target_amount, and never clears it afterward -- same "persists indefinitely" precedent
-- as users.password_changed_at (V40) and users.deactivated_at (V88).
ALTER TABLE goals ADD COLUMN completed_at TIMESTAMPTZ;
