-- jrdi V3 schema: enable real upsert on the 3 P2 framework tables, and add
-- `ref_bean_name` / `ref_id` columns to capture XML-discovered Dubbo configs.
--
-- Before this migration, `upsert()` in the repos was a plain `INSERT INTO`
-- which meant each re-index of an unchanged artifact would append duplicate
-- rows. With V3 we get idempotent upsert via `ON CONFLICT DO UPDATE`, and
-- we can safely re-run indexing without pre-deletion.
--
-- The new `ref_bean_name` / `ref_id` columns are empty for annotation-
-- discovered rows (where the Spring bean name isn't a thing — the impl
-- class itself is the bean) and non-empty for XML-discovered rows
-- (e.g. `<dubbo:service interface="X" ref="ownerServiceImpl"/>` records
-- ref_bean_name = "ownerServiceImpl").

-- ─── Add the new columns ─────────────────────────────────────────────
ALTER TABLE dubbo_services  ADD COLUMN ref_bean_name TEXT NOT NULL DEFAULT '';
ALTER TABLE dubbo_references ADD COLUMN ref_id        TEXT NOT NULL DEFAULT '';

-- ─── De-dup existing rows (idempotent on a fresh DB) ─────────────────
-- The pre-V3 schema had no UNIQUE constraint, so a re-index of an unchanged
-- artifact may have accumulated duplicates. Keep the lowest id of each
-- (interface_fqn, group_name, version, ref_bean_name, impl_class_id) tuple.
DELETE FROM dubbo_services
 WHERE id NOT IN (
       SELECT MIN(id) FROM dubbo_services
       GROUP BY interface_fqn, group_name, version, ref_bean_name, impl_class_id
 );

DELETE FROM dubbo_references
 WHERE id NOT IN (
       SELECT MIN(id) FROM dubbo_references
       GROUP BY interface_fqn, group_name, version, ref_id, field_id
 );

DELETE FROM mybatis_statements
 WHERE id NOT IN (
       SELECT MIN(id) FROM mybatis_statements
       GROUP BY namespace, statement_id
 );

-- ─── Promote the existing single-column indexes to UNIQUE composites ──
DROP INDEX IF EXISTS idx_dubbo_services_iface;
DROP INDEX IF EXISTS idx_dubbo_references_iface;
DROP INDEX IF EXISTS idx_mybatis_ns;

CREATE UNIQUE INDEX idx_dubbo_services_unique
    ON dubbo_services(interface_fqn, group_name, version, ref_bean_name, impl_class_id);

CREATE UNIQUE INDEX idx_dubbo_references_unique
    ON dubbo_references(interface_fqn, group_name, version, ref_id, field_id);

CREATE UNIQUE INDEX idx_mybatis_statements_unique
    ON mybatis_statements(namespace, statement_id);

-- ─── Keep the non-unique lookup indexes too (interface_fqn, field_id) ──
-- These are still useful for findByInterface / findByField queries that
-- don't need uniqueness.
CREATE INDEX idx_dubbo_services_iface        ON dubbo_services(interface_fqn);
CREATE INDEX idx_dubbo_references_iface      ON dubbo_references(interface_fqn);
CREATE INDEX idx_dubbo_references_field      ON dubbo_references(field_id);
CREATE INDEX idx_mybatis_ns                  ON mybatis_statements(namespace, statement_id);
