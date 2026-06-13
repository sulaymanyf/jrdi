-- jrdi V5 schema (PostgreSQL): Spring Boot auto-configuration discovery.
-- Mirrors V5__spring_boot_autoconfigs.sql in the SQLite dialect.

CREATE TABLE spring_boot_autoconfigs (
    id                BIGSERIAL PRIMARY KEY,
    class_fqn         TEXT    NOT NULL,
    source_file       TEXT    NOT NULL,
    source_format     TEXT    NOT NULL,
    key_in_factories  TEXT    NOT NULL DEFAULT '',
    UNIQUE(class_fqn, source_file, source_format, key_in_factories)
);

CREATE INDEX idx_spring_autoconfigs_class   ON spring_boot_autoconfigs(class_fqn);
CREATE INDEX idx_spring_autoconfigs_format  ON spring_boot_autoconfigs(source_format);
CREATE INDEX idx_spring_autoconfigs_key     ON spring_boot_autoconfigs(key_in_factories);
