-- BH-046 step 2 pre-flight check (read-only).
--
-- Purpose: before dropping the file_content BYTEA dual-write (V54), confirm every row that would
-- lose its only copy already has an object_key pointing at a real R2 object. Run against the prod
-- Railway Postgres by someone with direct access; this file makes no connection itself and should
-- not be pasted into chat with credentials attached.
--
-- All statements SELECT-only. Nothing here mutates data.

-- 1. Row counts, overall.
SELECT
    'statement_imports' AS table_name,
    count(*)                                                        AS total_rows,
    count(*) FILTER (WHERE file_content IS NOT NULL)                AS has_file_content,
    count(*) FILTER (WHERE object_key IS NOT NULL)                  AS has_object_key,
    count(*) FILTER (WHERE object_key IS NULL AND file_content IS NOT NULL) AS legacy_only_rows
FROM statement_imports
UNION ALL
SELECT
    'import_sessions',
    count(*),
    count(*) FILTER (WHERE file_content IS NOT NULL),
    count(*) FILTER (WHERE object_key IS NOT NULL),
    count(*) FILTER (WHERE object_key IS NULL AND file_content IS NOT NULL)
FROM import_sessions;

-- 2. The rows that matter: legacy-only, no object_key at all. If either query returns any rows,
--    step 2 is not safe yet -- those rows need a backfill (upload their file_content to R2 and set
--    object_key) before the column can be dropped without data loss.
SELECT id, created_at, content_hash, octet_length(file_content) AS bytes
FROM statement_imports
WHERE object_key IS NULL AND file_content IS NOT NULL
ORDER BY created_at
LIMIT 100;

SELECT id, created_at, content_hash, octet_length(file_content) AS bytes
FROM import_sessions
WHERE object_key IS NULL AND file_content IS NOT NULL
ORDER BY created_at
LIMIT 100;

-- 3. Distinct content hashes with an object_key, for a manual spot-check that the addressed object
--    actually exists in the R2 bucket (compare a sample of these against the bucket listing).
SELECT DISTINCT content_hash, object_key
FROM statement_imports
WHERE object_key IS NOT NULL
LIMIT 20;

-- Interpretation:
--   legacy_only_rows = 0 in both tables  -> every row already has an R2 copy; step 2 (drop the
--                                           dual-write / backfill nothing) can proceed after the
--                                           object-existence spot-check in query 3 passes.
--   legacy_only_rows > 0 in either table -> those specific rows (query 2) need their bytes pushed
--                                           to R2 and object_key set before file_content can go away.
