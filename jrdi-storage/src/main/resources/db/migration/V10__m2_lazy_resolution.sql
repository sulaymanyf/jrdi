-- jrdi V10 schema: lazy m2 resolution
--
-- 0.1.0-M1 stores framework records (dubbo_services, etc.) with
-- implClassId = 0 as a sentinel for "the implementation class lives
-- in a jar we didn't index". V10 makes that case resolvable at
-- query time: the lazy resolver opens the relevant m2 jar, runs a
-- lightweight ASM pass (classes + methods + invokes only, no
-- framework pass), and persists the result here.
--
-- Why a separate schema?
-- - Keeps the main "indexed project" tables clean. m2_* is by
--   definition second-class: partial facts, no source attribution,
--   no framework pass.
-- - Lets the LRU cache (m2_caches) evict without orphaning primary
--   facts. CASCADE on m2_methods → m2_classes handles the rest.
-- - Differentiates the "official" call graph (in `classes` /
--   `methods` / `invokes`) from the "best effort" extension
--   (m2_*). The LLM can tell at a glance which facts are
--   primary-source vs derived.

-- ─── m2_caches: per-jar ingestion state ────────────────────────────
-- One row per jar the lazy resolver has touched. (jar_path) is the
-- absolute filesystem path; (jar_sha256) is the content hash so a
-- same-path mtime change can be distinguished from a same-hash
-- rename. (jar_mtime_ms) is the cheap invalidation key — we stat
-- the file before trusting any cached row.
CREATE TABLE m2_caches (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    jar_path        TEXT    NOT NULL,
    jar_sha256      TEXT    NOT NULL,
    jar_mtime_ms    INTEGER NOT NULL,
    classes_count   INTEGER NOT NULL DEFAULT 0,
    methods_count   INTEGER NOT NULL DEFAULT 0,
    invokes_count   INTEGER NOT NULL DEFAULT 0,
    extracted_at    TEXT    NOT NULL,         -- ISO 8601
    last_access_at  TEXT    NOT NULL,         -- for LRU eviction
    UNIQUE(jar_path)
);
CREATE INDEX idx_m2_caches_sha ON m2_caches(jar_sha256);
CREATE INDEX idx_m2_caches_access ON m2_caches(last_access_at);

-- ─── m2_classes: lightweight class index ────────────────────────────
-- Mirrors a subset of the `classes` table columns, plus (jar_path)
-- for back-reference. We intentionally drop: file_id, signature_raw,
-- source (we don't have these for m2 classes — no source attribution
-- in this iteration).
CREATE TABLE m2_classes (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    fqn             TEXT    NOT NULL,         -- slashed form
    super_fqn       TEXT    NOT NULL DEFAULT '',
    access          INTEGER NOT NULL DEFAULT 0,
    interfaces      TEXT    NOT NULL DEFAULT '',  -- CSV slashed FQNs
    jar_path        TEXT    NOT NULL,             -- which jar this came from
    UNIQUE(fqn, jar_path)
);
CREATE INDEX idx_m2_classes_fqn ON m2_classes(fqn);

-- ─── m2_methods: lightweight method index ───────────────────────────
-- Mirrors `methods` minus: bytecode body, generic signature, source
-- line. The (line) column is best-effort — populated from the
-- LineNumberTable attribute if present, else NULL.
CREATE TABLE m2_methods (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    class_id        INTEGER NOT NULL REFERENCES m2_classes(id) ON DELETE CASCADE,
    name            TEXT    NOT NULL,
    desc            TEXT    NOT NULL,         -- JVM descriptor (e.g. (Ljava/lang/String;)V)
    access          INTEGER NOT NULL DEFAULT 0,
    line            INTEGER,
    UNIQUE(class_id, name, desc)
);
CREATE INDEX idx_m2_methods_class ON m2_methods(class_id);

-- ─── m2_invokes: cross-jar call edges ──────────────────────────────
-- We intentionally do NOT FK callee_class_fqn to m2_classes: the
-- callee may live in a jar we haven't resolved yet, or in a 3rd-party
-- jar that has no m2 cache row at all. The LLM query layer does the
-- left-join at read time. (call_kind) is the ASM-inferred kind:
-- 'invoke' for INVOKEVIRTUAL/INVOKESTATIC/INVOKEINTERFACE/INVOKESPECIAL,
-- 'invoke_dynamic' for INVOKEDYNAMIC (lambdas, string concat, etc.),
-- 'reflection' for Method.invoke / Class.forName patterns we can detect.
CREATE TABLE m2_invokes (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    caller_class_fqn    TEXT    NOT NULL,
    caller_method_id    INTEGER NOT NULL REFERENCES m2_methods(id) ON DELETE CASCADE,
    callee_class_fqn    TEXT    NOT NULL,
    callee_method_name  TEXT    NOT NULL,
    callee_method_desc  TEXT    NOT NULL,
    call_kind           TEXT    NOT NULL DEFAULT 'invoke'
);
CREATE INDEX idx_m2_invokes_caller ON m2_invokes(caller_class_fqn, caller_method_id);
CREATE INDEX idx_m2_invokes_callee ON m2_invokes(callee_class_fqn, callee_method_name);
