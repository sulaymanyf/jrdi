-- jrdi V6 schema (PostgreSQL): Spring Boot auto-config condition analysis.
-- Mirrors V6__spring_autoconfig_conditions.sql in the SQLite dialect.

CREATE TABLE spring_autoconfig_conditions (
    id                    BIGSERIAL PRIMARY KEY,
    autoconfig_class      TEXT    NOT NULL,
    condition_type        TEXT    NOT NULL,
    required_class        TEXT    NOT NULL DEFAULT '',
    required_bean_type    TEXT    NOT NULL DEFAULT '',
    required_property     TEXT    NOT NULL DEFAULT '',
    applied_to            TEXT    NOT NULL DEFAULT 'class',
    UNIQUE(autoconfig_class, condition_type, required_class, required_bean_type, required_property, applied_to)
);

CREATE INDEX idx_spring_autoconfig_conditions_class    ON spring_autoconfig_conditions(autoconfig_class);
CREATE INDEX idx_spring_autoconfig_conditions_required ON spring_autoconfig_conditions(required_class);
CREATE INDEX idx_spring_autoconfig_conditions_type     ON spring_autoconfig_conditions(condition_type);
