-- Financial Intelligence Workspace, Rule Management module: "how often does this rule actually
-- fire" was previously unanswerable -- category_rules had no counter and no last-fired timestamp.
-- Both are pure telemetry on an already-running evaluation (RuleEngineService), not new matching
-- logic or a new signal source, so this is in scope for the Workspace's "visualize what already
-- exists" phase the same way audit_log already is.
--
-- match_count is a plain counter, not soft-deletable/versioned data -- category_rules doesn't
-- extend BaseEntity's optimistic-locking pattern (see CategoryRule's own class comment), and a
-- lost-update race on a best-effort usage counter is an acceptable tradeoff for not adding
-- @Version machinery to an entity that deliberately doesn't have it.
ALTER TABLE category_rules ADD COLUMN match_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE category_rules ADD COLUMN last_matched_at TIMESTAMPTZ;
