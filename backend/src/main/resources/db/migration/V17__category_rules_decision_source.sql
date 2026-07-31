-- Rule Engine & Decision Source tracking (docs/rule-engine-relationship-engine-eds.md, EDS §3.1/3.2).
--
-- category_rules is deliberately more general than the static CategoryRules keyword table it
-- sits alongside (not replaces -- see EDS §4 pipeline order): field-based conditions instead of
-- description-only keyword matching, and actions beyond "assign category". scope distinguishes
-- system-seeded GLOBAL rules (read-only to users) from USER-authored ones; the CHECK constraint
-- keeps user_id NULL-iff-GLOBAL so a bug can't silently attribute a global rule to one user or
-- vice versa.
CREATE TABLE category_rules (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID REFERENCES users(id),
    scope             VARCHAR(10) NOT NULL,
    field             VARCHAR(20) NOT NULL,
    operator          VARCHAR(20) NOT NULL,
    comparison_value  TEXT NOT NULL,
    action_type       VARCHAR(20) NOT NULL,
    action_value      TEXT,
    priority          INT NOT NULL DEFAULT 100,
    enabled           BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_category_rules_scope_user
        CHECK ((scope = 'GLOBAL' AND user_id IS NULL) OR (scope = 'USER' AND user_id IS NOT NULL))
);

CREATE INDEX idx_category_rules_user ON category_rules(user_id) WHERE scope = 'USER';
CREATE INDEX idx_category_rules_scope_enabled ON category_rules(scope, enabled);

-- Decision Source: which mechanism produced a transaction's category, for explainability
-- ("why was this categorized this way"). Kept alongside category_manually_set (V12) rather than
-- replacing it -- that flag answers "did a human touch this", this answers "which of the several
-- automated mechanisms fired" when a human didn't. decision_rule_id is only ever set when
-- decision_source is GLOBAL_RULE or USER_RULE, letting a future "why" screen link straight back
-- to the rule that fired.
--
-- ON DELETE SET NULL (not the default NO ACTION/RESTRICT): decision_rule_id is explainability
-- metadata, not a real financial fact -- a transaction's amount/date/category must never
-- disappear just because the rule that once suggested its category was later deleted. Without
-- this, RuleService.delete() would fail with a raw DataIntegrityViolationException the first
-- time a user tried to delete any rule that had ever actually categorized a transaction, which
-- is the normal case for a rule worth having.
ALTER TABLE transactions ADD COLUMN decision_source VARCHAR(20) NOT NULL DEFAULT 'MERCHANT_DEFAULT';
ALTER TABLE transactions ADD COLUMN decision_rule_id UUID REFERENCES category_rules(id) ON DELETE SET NULL;
