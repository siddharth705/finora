-- Transaction Intelligence Phase B: the confidence percentage behind a category decision,
-- alongside decision_source/decision_rule_id (V17). Nullable and only ever set for a
-- SUGGESTED category (USER_RULE/GLOBAL_RULE/LEARNED_PATTERN/KEYWORD_MATCH/MERCHANT_DEFAULT) --
-- MANUAL and FILE_PROVIDED never populate this, because a human's or a source file's explicit
-- choice isn't a probabilistic guess with a confidence to report.
ALTER TABLE transactions ADD COLUMN decision_confidence INTEGER;
