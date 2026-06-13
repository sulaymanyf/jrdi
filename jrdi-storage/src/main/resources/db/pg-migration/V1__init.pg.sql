-- jrdi V1 schema (PostgreSQL dialect)
-- Mirrors V1__init.sql. Key differences from SQLite:
--   * SERIAL instead of INTEGER PRIMARY KEY AUTOINCREMENT
--   * TIMESTAMPTZ for indexed_at / detected_at (string-parsed in repos)
--   * BOOLEAN for has_sources / primary_b / virtual_lines
--   * BIGINT for ids (matches Hikari's PG-JDBC return type)

CREATE TABLE repos (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT    NOT NULL UNIQUE,
    root_path     TEXT    NOT NULL,
    vcs_rev       TEXT,
    indexed_at    TEXT    NOT NULL
);

CREATE TABLE artifacts (
    id            BIGSERIAL PRIMARY KEY,
    repo_id       BIGINT  NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    gav_group     TEXT    NOT NULL,
    gav_artifact  TEXT    NOT NULL,
    gav_version   TEXT    NOT NULL,
    sha256        TEXT,
    has_sources   INTEGER NOT NULL DEFAULT 0,
    jar_path      TEXT    NOT NULL,
    score         INTEGER NOT NULL DEFAULT 100,
    UNIQUE(repo_id, gav_group, gav_artifact, gav_version)
);

CREATE TABLE files (
    id            BIGSERIAL PRIMARY KEY,
    artifact_id   BIGINT  NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,
    rel_path      TEXT    NOT NULL,
    lang          TEXT    NOT NULL,
    mtime         BIGINT  NOT NULL,
    sha256        TEXT,
    UNIQUE(artifact_id, rel_path)
);

CREATE TABLE classes (
    id            BIGSERIAL PRIMARY KEY,
    fqn           TEXT    NOT NULL UNIQUE,
    access        INTEGER NOT NULL,
    super_fqn     TEXT,
    file_id       BIGINT  REFERENCES files(id) ON DELETE SET NULL,
    signature_raw TEXT,
    source        TEXT    NOT NULL DEFAULT 'jar'
);

CREATE TABLE methods (
    id            BIGSERIAL PRIMARY KEY,
    class_id      BIGINT  NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    name          TEXT    NOT NULL,
    "desc"        TEXT    NOT NULL,
    signature_raw TEXT,
    start_line    INTEGER,
    end_line      INTEGER,
    virtual_lines INTEGER NOT NULL DEFAULT 0,
    UNIQUE(class_id, name, "desc")
);

CREATE TABLE fields (
    id            BIGSERIAL PRIMARY KEY,
    class_id      BIGINT  NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    name          TEXT    NOT NULL,
    "desc"        TEXT    NOT NULL,
    signature_raw TEXT,
    line          INTEGER,
    UNIQUE(class_id, name, "desc")
);

CREATE TABLE invokes (
    id                BIGSERIAL PRIMARY KEY,
    caller_method_id  BIGINT  NOT NULL REFERENCES methods(id) ON DELETE CASCADE,
    callee_owner      TEXT    NOT NULL,
    callee_name       TEXT    NOT NULL,
    callee_desc       TEXT    NOT NULL,
    kind              TEXT    NOT NULL,
    line              INTEGER,
    confidence        TEXT    NOT NULL
);

CREATE TABLE lambdas (
    id                   BIGSERIAL PRIMARY KEY,
    enclosing_method_id  BIGINT  NOT NULL REFERENCES methods(id) ON DELETE CASCADE,
    synthetic_method_id  BIGINT  REFERENCES methods(id) ON DELETE CASCADE,
    bsm_target           TEXT,
    line                 INTEGER
);

CREATE TABLE issues (
    id          BIGSERIAL PRIMARY KEY,
    kind        TEXT    NOT NULL,
    target      TEXT    NOT NULL,
    message     TEXT    NOT NULL,
    severity    TEXT    NOT NULL,
    detected_at TEXT    NOT NULL
);

-- Indexes
CREATE INDEX idx_artifacts_gav      ON artifacts(gav_group, gav_artifact, gav_version);
CREATE INDEX idx_classes_fqn        ON classes(fqn);
CREATE INDEX idx_classes_super      ON classes(super_fqn);
CREATE INDEX idx_methods_class      ON methods(class_id);
CREATE INDEX idx_methods_name       ON methods(class_id, name);
CREATE INDEX idx_fields_class       ON fields(class_id);
CREATE INDEX idx_invokes_caller     ON invokes(caller_method_id);
CREATE INDEX idx_invokes_callee     ON invokes(callee_owner, callee_name, callee_desc);
CREATE INDEX idx_lambdas_enclosing  ON lambdas(enclosing_method_id);
CREATE INDEX idx_issues_kind        ON issues(kind, severity);

-- ─── P2: framework analyzer tables ──────────────────────────────────────────
CREATE TABLE spring_beans (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT    NOT NULL,
    type_fqn    TEXT    NOT NULL,
    source      TEXT    NOT NULL,
    class_id    BIGINT  REFERENCES classes(id) ON DELETE SET NULL,
    method_id   BIGINT  REFERENCES methods(id) ON DELETE SET NULL,
    scope       TEXT    NOT NULL DEFAULT 'singleton',
    primary_b   INTEGER NOT NULL DEFAULT 0,
    UNIQUE(name, type_fqn)
);

CREATE TABLE spring_injects (
    id                    BIGSERIAL PRIMARY KEY,
    target_field          TEXT,
    target_param_index    INTEGER,
    class_id              BIGINT  NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    method_id             BIGINT  REFERENCES methods(id) ON DELETE SET NULL,
    qualifier             TEXT,
    by_value              TEXT    NOT NULL,
    confidence            TEXT    NOT NULL,
    candidate_bean_ids    TEXT    NOT NULL DEFAULT '[]'
);

CREATE TABLE dubbo_services (
    id              BIGSERIAL PRIMARY KEY,
    interface_fqn   TEXT    NOT NULL,
    impl_class_id   BIGINT  NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    group_name      TEXT    NOT NULL DEFAULT '',
    version         TEXT    NOT NULL DEFAULT '',
    protocol        TEXT    NOT NULL DEFAULT 'dubbo',
    source          TEXT    NOT NULL
);

CREATE TABLE dubbo_references (
    id              BIGSERIAL PRIMARY KEY,
    interface_fqn   TEXT    NOT NULL,
    field_id        BIGINT  NOT NULL REFERENCES fields(id) ON DELETE CASCADE,
    group_name      TEXT    NOT NULL DEFAULT '',
    version         TEXT    NOT NULL DEFAULT '',
    confidence      TEXT    NOT NULL
);

CREATE TABLE mybatis_statements (
    id                BIGSERIAL PRIMARY KEY,
    namespace         TEXT    NOT NULL,
    statement_id      TEXT    NOT NULL,
    kind              TEXT    NOT NULL,
    sql_template      TEXT    NOT NULL,
    sql_normalized    TEXT    NOT NULL,
    parameters        TEXT    NOT NULL DEFAULT '[]',
    defined_in_file   TEXT    NOT NULL,
    line              INTEGER
);

CREATE INDEX idx_spring_beans_type        ON spring_beans(type_fqn);
CREATE INDEX idx_spring_injects_class    ON spring_injects(class_id);
CREATE INDEX idx_dubbo_services_iface    ON dubbo_services(interface_fqn);
CREATE INDEX idx_dubbo_references_iface  ON dubbo_references(interface_fqn);
CREATE INDEX idx_mybatis_ns              ON mybatis_statements(namespace, statement_id);
