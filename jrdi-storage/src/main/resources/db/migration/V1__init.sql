-- jrdi V1 schema (SQLite dialect)
-- ANSI-friendly subset; will be mirrored by V1__init.pg.sql for PG.

CREATE TABLE repos (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT    NOT NULL UNIQUE,
    root_path     TEXT    NOT NULL,
    vcs_rev       TEXT,
    indexed_at    TEXT    NOT NULL
);

CREATE TABLE artifacts (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    repo_id       INTEGER NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
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
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    artifact_id   INTEGER NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,
    rel_path      TEXT    NOT NULL,
    lang          TEXT    NOT NULL,
    mtime         INTEGER NOT NULL,
    sha256        TEXT,
    UNIQUE(artifact_id, rel_path)
);

CREATE TABLE classes (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    fqn           TEXT    NOT NULL UNIQUE,
    access        INTEGER NOT NULL,
    super_fqn     TEXT,
    file_id       INTEGER REFERENCES files(id) ON DELETE SET NULL,
    signature_raw TEXT,
    source        TEXT    NOT NULL DEFAULT 'jar'
);

CREATE TABLE methods (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    class_id      INTEGER NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    name          TEXT    NOT NULL,
    "desc"        TEXT    NOT NULL,
    signature_raw TEXT,
    start_line    INTEGER,
    end_line      INTEGER,
    virtual_lines INTEGER NOT NULL DEFAULT 0,
    UNIQUE(class_id, name, "desc")
);

CREATE TABLE fields (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    class_id      INTEGER NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    name          TEXT    NOT NULL,
    "desc"        TEXT    NOT NULL,
    signature_raw TEXT,
    line          INTEGER,
    UNIQUE(class_id, name, "desc")
);

CREATE TABLE invokes (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    caller_method_id  INTEGER NOT NULL REFERENCES methods(id) ON DELETE CASCADE,
    callee_owner      TEXT    NOT NULL,
    callee_name       TEXT    NOT NULL,
    callee_desc       TEXT    NOT NULL,
    kind              TEXT    NOT NULL,
    line              INTEGER,
    confidence        TEXT    NOT NULL
);

CREATE TABLE lambdas (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    enclosing_method_id  INTEGER NOT NULL REFERENCES methods(id) ON DELETE CASCADE,
    synthetic_method_id  INTEGER REFERENCES methods(id) ON DELETE CASCADE,
    bsm_target           TEXT,
    line                 INTEGER
);

CREATE TABLE issues (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
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
-- spring_beans: every Spring-managed bean, where it was discovered.
--   "source" is "annotation" (component-scan) / "config" (@Bean) / "xml" /
--   "factory" (spring.factories).
CREATE TABLE spring_beans (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL,
    type_fqn    TEXT    NOT NULL,
    source      TEXT    NOT NULL,
    class_id    INTEGER REFERENCES classes(id) ON DELETE SET NULL,
    method_id   INTEGER REFERENCES methods(id) ON DELETE SET NULL,
    scope       TEXT    NOT NULL DEFAULT 'singleton',
    primary_b   INTEGER NOT NULL DEFAULT 0,
    UNIQUE(name, type_fqn)
);

-- spring_injects: a @Autowired / @Resource / @Value site and its candidate beans.
--   "by_value" is "type" / "name" / "qualifier" / "value" (renamed from "by" to avoid
--   the SQL keyword).
--   candidate_bean_ids is a JSON array (best-effort; may be empty when no match).
CREATE TABLE spring_injects (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    target_field          TEXT,
    target_param_index    INTEGER,
    class_id              INTEGER NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    method_id             INTEGER REFERENCES methods(id) ON DELETE SET NULL,
    qualifier             TEXT,
    by_value              TEXT    NOT NULL,
    confidence            TEXT    NOT NULL,
    candidate_bean_ids    TEXT    NOT NULL DEFAULT '[]'
);

-- dubbo_services: provider side. One row per @DubboService / @Service / XML.
CREATE TABLE dubbo_services (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    interface_fqn   TEXT    NOT NULL,
    impl_class_id   INTEGER NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    group_name      TEXT    NOT NULL DEFAULT '',
    version         TEXT    NOT NULL DEFAULT '',
    protocol        TEXT    NOT NULL DEFAULT 'dubbo',
    source          TEXT    NOT NULL
);

-- dubbo_references: consumer side. One row per @DubboReference / @Reference / XML.
CREATE TABLE dubbo_references (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    interface_fqn   TEXT    NOT NULL,
    field_id        INTEGER NOT NULL REFERENCES fields(id) ON DELETE CASCADE,
    group_name      TEXT    NOT NULL DEFAULT '',
    version         TEXT    NOT NULL DEFAULT '',
    confidence      TEXT    NOT NULL
);

-- mybatis_statements: a parsed SQL statement with its parameter set.
--   "kind" is "select" / "insert" / "update" / "delete".
--   parameters is a JSON array of param name + java type.
CREATE TABLE mybatis_statements (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
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
