-- jrdi V7 schema: Dubbo registry tracking.
--
-- Spring schema-style Dubbo configs can declare registries:
--   <dubbo:registry id="zk" address="zookeeper://10.0.0.1:2181"/>
--   <dubbo:registry id="nacos" address="nacos://10.0.0.2:8848"/>
--
-- And services / references can target a specific registry:
--   <dubbo:service interface="..." ref="..." registry="zk"/>
--   <dubbo:reference id="..." interface="..." registry="nacos"/>
--
-- We record every <dubbo:registry/> declaration as a row, with the
-- address (URL) and protocol. Then we extend dubbo_services and
-- dubbo_references with a new column "registry_id" pointing at the
-- registry row (nullable; "" means "no registry specified, use the
-- default"). This lets the LLM answer:
--   "which services are registered with ZooKeeper vs Nacos?"
--   "which interfaces have no registry binding (default)?"

CREATE TABLE dubbo_registries (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    registry_id     TEXT    NOT NULL,    -- the "id" attribute of <dubbo:registry/>
    address         TEXT    NOT NULL DEFAULT '',
    protocol        TEXT    NOT NULL DEFAULT '',
    port            INTEGER,
    username        TEXT    NOT NULL DEFAULT '',
    parameters      TEXT    NOT NULL DEFAULT '{}',  -- raw <dubbo:parameter key=val ...>
    source_file     TEXT    NOT NULL DEFAULT '',
    UNIQUE(source_file, registry_id)
);

-- Default registry for services / references that don't specify one.
-- We don't model this — services without a registry implicitly use the
-- project's "default" registry, which the user can find by looking at
-- the project's <dubbo:registry> declarations and Spring's default-protocol
-- logic.
ALTER TABLE dubbo_services  ADD COLUMN registry_id TEXT NOT NULL DEFAULT '';
ALTER TABLE dubbo_references ADD COLUMN registry_id TEXT NOT NULL DEFAULT '';

CREATE INDEX idx_dubbo_registries_id     ON dubbo_registries(registry_id);
CREATE INDEX idx_dubbo_services_registry  ON dubbo_services(registry_id);
CREATE INDEX idx_dubbo_references_registry ON dubbo_references(registry_id);

-- V3 had a UNIQUE index on (interface_fqn, group_name, version, ref_bean_name, impl_class_id).
-- With registry_id now part of the natural key, the same service can be
-- registered against multiple registries. Drop the old UNIQUE index and
-- replace it with a wider one. (For a fresh DB V3's index never existed
-- in the first place, so this DROP is a no-op.)
DROP INDEX IF EXISTS idx_dubbo_services_unique;
DROP INDEX IF EXISTS idx_dubbo_references_unique;
CREATE UNIQUE INDEX idx_dubbo_services_unique
    ON dubbo_services(interface_fqn, group_name, version, ref_bean_name, impl_class_id, registry_id);
CREATE UNIQUE INDEX idx_dubbo_references_unique
    ON dubbo_references(interface_fqn, group_name, version, ref_id, field_id, registry_id);
