-- V9: add consumer_class_fqn to dubbo_references so cross-jar references
-- (where the @DubboReference lives in a dependency outside the indexed module)
-- can still report the consumer class. Nullable; for in-module references the
-- class is also reachable via field_id -> fields.class_id -> classes.
--
-- We also DROP the V3 UNIQUE index and re-create it including
-- consumer_class_fqn. Without that, two consumers in different classes (both
-- with field_id=0 in the cross-jar case) would collide on the same row.
DROP INDEX IF EXISTS idx_dubbo_references_unique;
ALTER TABLE dubbo_references ADD COLUMN consumer_class_fqn TEXT NOT NULL DEFAULT '';
CREATE INDEX idx_dubbo_refs_consumer ON dubbo_references(consumer_class_fqn);
CREATE UNIQUE INDEX idx_dubbo_references_unique
    ON dubbo_references(interface_fqn, group_name, version, ref_id, field_id, registry_id, consumer_class_fqn);
