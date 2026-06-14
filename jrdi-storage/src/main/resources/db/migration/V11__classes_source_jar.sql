-- jrdi V11 schema: project + direct deps in one index
--
-- 0.1.0-M1 and 0.2.0-M1 stored only the user's project in
-- the main `classes` / `methods` / `invokes` tables, and pushed
-- m2-extracted facts into a separate `m2_*` schema. That double
-- schema forced every LLM query to UNION the two and decide which
-- subset to trust. Worse, `find_path` and `callees_of` couldn't
-- follow a cross-jar edge naturally — they had to know about the
-- split.
--
-- V11 collapses both into a single schema, with a `source_jar`
-- column on `classes` (and CASCADE on `methods` / `invokes` /
-- `fields` / `lambdas`) to distinguish "this class lives in your
-- project" (source_jar = '') from "this class lives in a
-- dependency we lazily extracted" (source_jar = '/abs/path/...jar').
--
-- The existing `m2_*` tables from V10 are NOT dropped here — we
-- keep them as dormant schema for one release so any in-flight
-- `m2_classes` reads from older deployments still work. They are
-- scheduled for removal in 0.4.0 once we have telemetry proving
-- nobody's reading them.

-- ─── Add source_jar to classes ───────────────────────────────────
-- Empty string means "user's own project". Non-empty is an
-- absolute path to a jar in the local m2 cache (or any directory
-- the operator pointed us at via --m2-cache-dir).
ALTER TABLE classes ADD COLUMN source_jar TEXT NOT NULL DEFAULT '';
CREATE INDEX idx_classes_source_jar ON classes(source_jar);

-- ─── Index for cross-jar call lookups ─────────────────────────────
-- `callees_of` joins invokes to methods; with on-miss extraction
-- we want to make sure the join can land on either project classes
-- or m2-extracted classes via a single index. The existing
-- `idx_invokes_caller` and `idx_invokes_callee` already cover the
-- common cases; the new index accelerates the LLM's frequent
-- "all classes in this jar" query.
CREATE INDEX idx_methods_class_jar
    ON methods(class_id, name)
    WHERE name IS NOT NULL;
