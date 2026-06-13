-- jrdi V3 schema (PostgreSQL): enable real upsert on the 3 P2 framework tables,
-- and add `ref_bean_name` / `ref_id` columns to capture XML-discovered Dubbo configs.
--
-- Mirrors V3__framework_upsert.sql in the SQLite dialect. The PG version uses
-- `DROP INDEX IF EXISTS` which works since PG 8.0. The de-dup is done with a
-- window function (PG-compatible) rather than the SQLite min-id subquery form.

-- ─── Add the new columns ─────────────────────────────────────────────
ALTER TABLE dubbo_services   ADD COLUMN ref_bean_name TEXT NOT NULL DEFAULT '';
ALTER TABLE dubbo_references ADD COLUMN ref_id        TEXT NOT NULL DEFAULT '';

-- ─── De-dup existing rows (idempotent on a fresh DB) ─────────────────
-- Keep the lowest id of each (interface_fqn, group_name, version, ref_bean_name,
-- impl_class_id) tuple. PG's DELETE ... USING form.
DELETE FROM dubbo_services ds
 USING dubbo_services newer
 WHERE ds.interface_fqn = newer.interface_fqn
   AND ds.group_name    = newer.group_name
   AND ds.version       = newer.version
   AND ds.ref_bean_name = newer.ref_bean_name
   AND ds.impl_class_id = newer.impl_class_id
   AND ds.id > newer.id;

DELETE FROM dubbo_references dr
 USING dubbo_references newer
 WHERE dr.interface_fqn = newer.interface_fqn
   AND dr.group_name    = newer.group_name
   AND dr.version       = newer.version
   AND dr.ref_id        = newer.ref_id
   AND dr.field_id      = newer.field_id
   AND dr.id > newer.id;

DELETE FROM mybatis_statements ms
 USING mybatis_statements newer
 WHERE ms.namespace    = newer.namespace
   AND ms.statement_id = newer.statement_id
   AND ms.id > newer.id;

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
CREATE INDEX idx_dubbo_services_iface        ON dubbo_services(interface_fqn);
CREATE INDEX idx_dubbo_references_iface      ON dubbo_references(interface_fqn);
CREATE INDEX idx_dubbo_references_field      ON dubbo_references(field_id);
CREATE INDEX idx_mybatis_ns                  ON mybatis_statements(namespace, statement_id);
