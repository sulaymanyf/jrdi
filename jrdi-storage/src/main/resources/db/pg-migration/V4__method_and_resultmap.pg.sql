-- jrdi V4 schema (PostgreSQL): per-method Dubbo config + MyBatis resultMap.
-- Mirrors V4__method_and_resultmap.sql in the SQLite dialect.

CREATE TABLE dubbo_method_configs (
    id              BIGSERIAL PRIMARY KEY,
    service_id      BIGINT  NOT NULL DEFAULT 0 REFERENCES dubbo_services(id) ON DELETE CASCADE,
    reference_id    BIGINT  NOT NULL DEFAULT 0 REFERENCES dubbo_references(id) ON DELETE CASCADE,
    method_name     TEXT    NOT NULL,
    timeout_ms      INTEGER,
    retries         INTEGER,
    loadbalance     TEXT,
    async           INTEGER NOT NULL DEFAULT 0,
    sent            INTEGER NOT NULL DEFAULT 0,
    UNIQUE(service_id, reference_id, method_name)
);

CREATE INDEX idx_dubbo_method_configs_service ON dubbo_method_configs(service_id);
CREATE INDEX idx_dubbo_method_configs_ref     ON dubbo_method_configs(reference_id);

CREATE TABLE mybatis_result_maps (
    id                BIGSERIAL PRIMARY KEY,
    namespace         TEXT    NOT NULL,
    map_id            TEXT    NOT NULL,
    type_fqn          TEXT    NOT NULL DEFAULT '',
    extends_          TEXT    NOT NULL DEFAULT '',
    auto_mapping      INTEGER NOT NULL DEFAULT 0,
    property_count    INTEGER NOT NULL DEFAULT 0,
    association_count INTEGER NOT NULL DEFAULT 0,
    collection_count  INTEGER NOT NULL DEFAULT 0,
    UNIQUE(namespace, map_id)
);

CREATE INDEX idx_mybatis_result_maps_ns   ON mybatis_result_maps(namespace);
CREATE INDEX idx_mybatis_result_maps_type ON mybatis_result_maps(type_fqn);
