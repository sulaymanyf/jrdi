-- jrdi V7 schema (PostgreSQL): Dubbo registry tracking.
-- Mirrors V7__dubbo_registries.sql in the SQLite dialect.

CREATE TABLE dubbo_registries (
    id              BIGSERIAL PRIMARY KEY,
    registry_id     TEXT    NOT NULL,
    address         TEXT    NOT NULL DEFAULT '',
    protocol        TEXT    NOT NULL DEFAULT '',
    port            INTEGER,
    username        TEXT    NOT NULL DEFAULT '',
    parameters      TEXT    NOT NULL DEFAULT '{}',
    source_file     TEXT    NOT NULL DEFAULT '',
    UNIQUE(source_file, registry_id)
);

ALTER TABLE dubbo_services  ADD COLUMN registry_id TEXT NOT NULL DEFAULT '';
ALTER TABLE dubbo_references ADD COLUMN registry_id TEXT NOT NULL DEFAULT '';

CREATE INDEX idx_dubbo_registries_id     ON dubbo_registries(registry_id);
CREATE INDEX idx_dubbo_services_registry  ON dubbo_services(registry_id);
CREATE INDEX idx_dubbo_references_registry ON dubbo_references(registry_id);

DROP INDEX IF EXISTS idx_dubbo_services_unique;
DROP INDEX IF EXISTS idx_dubbo_references_unique;
CREATE UNIQUE INDEX idx_dubbo_services_unique
    ON dubbo_services(interface_fqn, group_name, version, ref_bean_name, impl_class_id, registry_id);
CREATE UNIQUE INDEX idx_dubbo_references_unique
    ON dubbo_references(interface_fqn, group_name, version, ref_id, field_id, registry_id);
