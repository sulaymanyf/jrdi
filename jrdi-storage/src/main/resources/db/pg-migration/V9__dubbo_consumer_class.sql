-- V9 (PG): add consumer_class_fqn to dubbo_references, and re-create the
-- UNIQUE index to include it.
DROP INDEX IF EXISTS idx_dubbo_references_unique;
ALTER TABLE dubbo_references ADD COLUMN consumer_class_fqn TEXT NOT NULL DEFAULT '';
CREATE INDEX idx_dubbo_refs_consumer ON dubbo_references(consumer_class_fqn);
CREATE UNIQUE INDEX idx_dubbo_references_unique
    ON dubbo_references(interface_fqn, group_name, version, ref_id, field_id, registry_id, consumer_class_fqn);
