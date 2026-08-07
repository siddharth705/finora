-- Milestone 2 item 2 (docs/engineering/milestone-2-import-at-scale.md §2): a layout becomes a row.
--
-- WHAT IS ACTUALLY MISSING TODAY
-- ------------------------------
-- layout_fingerprint has been on statement_imports since V39 and DocumentContext.buildFingerprint()
-- fills it on every import, so layouts are already OBSERVABLE. What they are not is CURATABLE:
-- LayoutIntelligenceService owns no repository and re-derives every answer by grouping
-- statement_imports on that string column. A layout cannot be named, approved, tied to a parser or
-- given a first/last-seen of its own, because a layout is not a row anywhere -- it is a GROUP BY.
--
-- Two consequences a fingerprint column cannot fix on its own, and they are the reason this table
-- exists rather than another index on the old one:
--
-- 1. A layout's history dies with the statements that produced it. statement_imports is
--    soft-deleted (@SQLRestriction("deleted_at IS NULL") on the entity), so a user deleting their
--    three uploads also deletes the only evidence Finora ever saw that layout. "Coverage" derived
--    that way is not a number that moves -- it is a number that moves BACKWARDS for reasons that
--    have nothing to do with the parser, and no one reading it would know why.
-- 2. "Every layout Finora claims to support" (success criterion 4) has nowhere to live. A claim is
--    not derivable from history: history records what was seen, never what we stand behind. The
--    list criterion 4 requires "in writing" is status = 'SUPPORTED' below.
--
-- OBSERVED COLUMNS vs CURATED COLUMNS -- the rule that governs every write here
-- ----------------------------------------------------------------------------
-- This table deliberately mixes two kinds of fact:
--   observed -- source_format, parser, first_seen, last_seen, observation_count. The engine writes
--               these, once per confirmed import, and nothing human edits them.
--   curated  -- name, status. Only an operator writes these (the curation screen is item 7; this
--               migration is the persistence model it will write into, and nothing more).
-- RegisteredLayoutRepository.observe()'s upsert touches ONLY the observed columns. That split is
-- the point rather than a detail of it: if an observation reset status, a layout an operator had
-- marked SUPPORTED would silently fall back to OBSERVED the next time anybody imported it, and the
-- supported-layout list would erode by being used. The same applies to name -- re-importing a
-- statement must never rename the layout back to nothing.

CREATE TABLE layout_registry (
    id                UUID PRIMARY KEY,

    -- The natural key, and the join back to everything already recorded. Same VARCHAR(20) as
    -- statement_imports.layout_fingerprint (V39) so the two can never disagree about what fits.
    --
    -- Deliberately NOT a foreign key to anything. A registry row has to outlive every import that
    -- produced it -- that is failure 1 above -- so there is no parent row to reference, and a
    -- fingerprint recorded here may legitimately have zero surviving statement_imports.
    fingerprint       VARCHAR(20)  NOT NULL UNIQUE,

    -- Curated. Null until an operator names it: a generated placeholder ("Layout FP-1-A1B2C3D4")
    -- would be indistinguishable from a real name at a glance, which makes "how many layouts have
    -- we actually identified" unanswerable -- the exact question the registry exists to answer.
    name              TEXT,

    -- OBSERVED     -- the engine has seen it; nobody has looked at it yet. Every backfilled row.
    -- UNDER_REVIEW -- an operator has picked it up.
    -- SUPPORTED    -- Finora claims to handle this layout. THIS is criterion 4's list.
    -- UNSUPPORTED  -- reviewed, and deliberately not claimed. Distinct from OBSERVED, which means
    --                 only that nobody has decided; conflating the two would let an unreviewed
    --                 backlog masquerade as a set of considered decisions.
    --
    -- Not the merchants vocabulary from V64 (TEMPORARY/UNDER_REVIEW/APPROVED), despite the
    -- temptation to reuse it. "Approved" reads as a judgement about the document; the judgement
    -- here is about the parser -- whether WE support it -- and SUPPORTED/UNSUPPORTED says that.
    status            VARCHAR(16)  NOT NULL DEFAULT 'OBSERVED',

    -- Observed. "PDF" or "CSV". Redundant with the fingerprint in principle -- source format is one
    -- of the v1 fingerprint spec's hash inputs, so every import of one fingerprint carries the same
    -- one -- but stored rather than decoded, because the fingerprint is a SHA-256 prefix and
    -- nothing can read the format back out of it.
    --
    -- VARCHAR(10) to match statement_imports.source_format (V36) exactly, not because "PDF" needs
    -- the room. The backfill below copies that column straight across, and a narrower type here
    -- would turn the first source format longer than this one into a migration that fails on
    -- deploy -- for data that was already perfectly valid where it came from.
    source_format     VARCHAR(10),

    -- Observed. The extractor that last produced this layout (FinancialDocumentMetadata.parser,
    -- e.g. "PdfPreviewGenerator"). Observed and not curated on purpose: which parser handles a
    -- layout is a fact the engine already knows on every run, and a hand-assigned value would go
    -- stale silently the first time extraction moved.
    parser            VARCHAR(64),

    -- Observed, and the whole reason first/last-seen belong on this row rather than being computed
    -- from statement_imports: these survive the deletion of every statement that produced them.
    first_seen        TIMESTAMPTZ  NOT NULL,
    last_seen         TIMESTAMPTZ  NOT NULL,

    -- Monotonic count of confirmed imports of this layout, incremented once per import and never
    -- re-derived. It will drift ABOVE the live aggregate LayoutIntelligenceService reports, and
    -- that gap is the useful part: "seen 40 times, 3 statements survive" says deletion is eroding
    -- the sample, which a single count cannot. If the two never diverge in practice, this column
    -- has proved itself unnecessary -- which is a result worth being able to reach.
    observation_count BIGINT       NOT NULL DEFAULT 0,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT layout_registry_status_valid CHECK (status IN (
        'OBSERVED','UNDER_REVIEW','SUPPORTED','UNSUPPORTED'))
);

-- No index beyond the UNIQUE above, deliberately. This table holds one row per DISTINCT document
-- structure, which is bounded by how many layouts exist in the world rather than by traffic --
-- hundreds, not millions -- so the planner will sequentially scan it whatever we build, and an
-- index on status would be maintenance with no reader. Add one when a query proves it needs one.

-- ---------------------------------------------------------------------------------------------
-- BACKFILL: adopt every fingerprint already in import history.
--
-- Without this the registry starts empty and the layouts Finora has ALREADY seen -- the only
-- evidence anyone has about which structures matter -- would be invisible until each happened to be
-- imported again. That is the "do not orphan history" requirement, and it is also the difference
-- between an operator opening the registry on a populated list and opening it on nothing.
--
-- Includes soft-deleted imports (no `deleted_at IS NULL` filter), which is the one line here worth
-- arguing about. A deleted statement is a statement the user no longer wants; it is not evidence
-- the layout was never encountered. Filtering them out would reintroduce failure 1 at the exact
-- moment we are fixing it.
--
-- MIN(source_format) is a grouping formality, not a choice between candidates: source format is a
-- hash input to the fingerprint (see DocumentContext.LAYOUT_FINGERPRINT_VERSION's v1 spec), so
-- every row in one group carries the same value by construction.
--
-- parser is left NULL rather than decoded out of layout_metadata_json. That column is TEXT, not
-- JSONB, so reading it here means an unguarded ::jsonb cast on data this migration has never
-- validated -- one malformed row, written by any code path over the last thirty migrations, and
-- the deploy fails. Live observation fills parser in on the next import of each layout, and a NULL
-- until then is honest.
--
-- GROUP BY is what guarantees one row per fingerprint; no ON CONFLICT clause is needed because the
-- table was created empty four statements ago.
INSERT INTO layout_registry (
    id, fingerprint, source_format, status, first_seen, last_seen, observation_count,
    created_at, updated_at)
SELECT gen_random_uuid(),
       si.layout_fingerprint,
       MIN(si.source_format),
       'OBSERVED',
       MIN(si.imported_at),
       MAX(si.imported_at),
       COUNT(*),
       now(),
       now()
  FROM statement_imports si
 WHERE si.layout_fingerprint IS NOT NULL
 GROUP BY si.layout_fingerprint;

-- Deliberately NOT sourced from import_sessions or statement_analysis_sessions, both of which also
-- carry a layout_fingerprint. Staging writes nothing durable (Milestone 1), and an analysis session
-- is an operator running the engine on a document Finora was never asked to import -- registering
-- either would put layouts in the registry that no user ever actually imported, and "how many
-- layouts do we see in production" would stop meaning that.

COMMENT ON TABLE layout_registry IS
    'One row per distinct document layout, keyed by DocumentContext fingerprint. Mixes engine-'
    'observed columns (source_format, parser, first/last_seen, observation_count) with '
    'operator-curated ones (name, status); an observation never writes a curated column.';

COMMENT ON COLUMN layout_registry.status IS
    'OBSERVED (seen, unreviewed), UNDER_REVIEW, SUPPORTED (the layouts Finora claims to handle -- '
    'Milestone 2 success criterion 4''s list) or UNSUPPORTED (reviewed and deliberately not '
    'claimed). Never written by an import.';

COMMENT ON COLUMN layout_registry.observation_count IS
    'Confirmed imports of this layout, ever. Monotonic and never re-derived, so it counts imports '
    'whose statements have since been deleted -- the gap against the live aggregate is the signal.';

COMMENT ON COLUMN layout_registry.first_seen IS
    'When this layout was first imported. Lives here rather than being computed from '
    'statement_imports so that deleting every statement of a layout does not erase its history.';
