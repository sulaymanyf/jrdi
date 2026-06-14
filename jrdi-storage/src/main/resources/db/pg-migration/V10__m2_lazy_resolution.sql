-- V10 (PG): lazy m2 resolution — same shape as the SQLite migration
-- with PG-specific column types.
CREATE TABLE m2_caches (
    id              BIGSERIAL PRIMARY KEY,
    jar_path        TEXT      NOT NULL,
    jar_sha256      TEXT      NOT NULL,
    jar_mtime_ms    BIGINT    NOT NULL,
    classes_count   INTEGER   NOT NULL DEFAULT 0,
    methods_count   INTEGER   NOT NULL DEFAULT 0,
    invokes_count   INTEGER   NOT NULL DEFAULT 0,
    extracted_at    TEXT      NOT NULL,
    last_access_at  TEXT      NOT NULL,
    UNIQUE(jar_path)
);
CREATE INDEX idx_m2_caches_sha     ON m2_caches(jar_sha256);
CREATE INDEX idx_m2_caches_access  ON m2_caches(last_access_at);

CREATE TABLE m2_classes (
    id              BIGSERIAL PRIMARY KEY,
    fqn             TEXT    NOT NULL,
    super_fqn       TEXT    NOT NULL DEFAULT '',
    access          INTEGER NOT NULL DEFAULT 0,
    interfaces      TEXT    NOT NULL DEFAULT '',
    jar_path        TEXT    NOT NULL,
    UNIQUE(fqn, jar_path)
);
CREATE INDEX idx_m2_classes_fqn ON m2_classes(fqn);

CREATE TABLE m2_methods (
    id              BIGSERIAL PRIMARY KEY,
    class_id        BIGINT  NOT NULL REFERENCES m2_classes(id) ON DELETE CASCADE,
    name            TEXT    NOT NULL,
    desc            TEXT    NOT NULL,
    access          INTEGER NOT NULL DEFAULT 0,
    line            INTEGER,
    UNIQUE(class_id, name, desc)
);
CREATE INDEX idx_m2_methods_class ON m2_methods(class_id);

CREATE TABLE m2_invokes (
    id                  BIGSERIAL PRIMARY KEY,
    caller_class_fqn    TEXT    NOT NULL,
    caller_method_id    BIGINT  NOT NULL REFERENCES m2_methods(id) ON DELETE CASCADE,
    callee_class_fqn    TEXT    NOT NULL,
    callee_method_name  TEXT    NOT NULL,
    callee_method_desc  TEXT    NOT NULL,
    call_kind           TEXT    NOT NULL DEFAULT 'invoke'
);
CREATE INDEX idx_m2_invokes_caller ON m2_invokes(caller_class_fqn, caller_method_id);
CREATE INDEX idx_m2_invokes_callee ON m2_invokes(callee_class_fqn, callee_method_name);
