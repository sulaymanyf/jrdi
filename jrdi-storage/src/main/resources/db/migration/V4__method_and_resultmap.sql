-- jrdi V4 schema: per-method Dubbo config + MyBatis resultMap discovery.
--
-- P2.7+ adds the missing half of the framework XML coverage:
--   1. <dubbo:method> children of <dubbo:service> / <dubbo:reference> — these
--      carry per-method timeouts, retries, loadbalancer, async, etc. Until V4
--      we silently dropped them. Now they get their own table keyed by the
--      parent service row.
--   2. <resultMap> elements in MyBatis mapper files — the row-mapper shape
--      that turns SQL columns into Java fields. This is the second half of
--      "what does this query return" (the first half being the SQL itself,
--      which we've always had).

CREATE TABLE dubbo_method_configs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    service_id      INTEGER NOT NULL DEFAULT 0 REFERENCES dubbo_services(id) ON DELETE CASCADE,
    reference_id    INTEGER NOT NULL DEFAULT 0 REFERENCES dubbo_references(id) ON DELETE CASCADE,
    method_name     TEXT    NOT NULL,
    -- Per-method knobs. NULL = "not configured" (we keep the row, just don't
    -- pretend the developer set a value).
    timeout_ms      INTEGER,
    retries         INTEGER,
    loadbalance     TEXT,
    async           INTEGER NOT NULL DEFAULT 0,
    sent            INTEGER NOT NULL DEFAULT 0,
    -- Optional target method signature (for <dubbo:method name="x" .../> on the
    -- consumer side, points at the interface method).
    UNIQUE(service_id, reference_id, method_name)
);

-- When a row has reference_id=0 and service_id=N, it's a provider method config.
-- When it has service_id=0 and reference_id=N, it's a consumer-side method config.
-- Both being 0 is a no-op row (filtered in queries).

CREATE INDEX idx_dubbo_method_configs_service ON dubbo_method_configs(service_id);
CREATE INDEX idx_dubbo_method_configs_ref     ON dubbo_method_configs(reference_id);

CREATE TABLE mybatis_result_maps (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    namespace        TEXT    NOT NULL,
    map_id           TEXT    NOT NULL,
    type_fqn         TEXT    NOT NULL DEFAULT '',
    extends_         TEXT    NOT NULL DEFAULT '',
    auto_mapping     INTEGER NOT NULL DEFAULT 0,
    property_count   INTEGER NOT NULL DEFAULT 0,
    association_count INTEGER NOT NULL DEFAULT 0,
    collection_count  INTEGER NOT NULL DEFAULT 0,
    UNIQUE(namespace, map_id)
);

CREATE INDEX idx_mybatis_result_maps_ns ON mybatis_result_maps(namespace);
CREATE INDEX idx_mybatis_result_maps_type ON mybatis_result_maps(type_fqn);
