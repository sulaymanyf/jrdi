-- jrdi V6 schema: Spring Boot auto-config condition analysis.
--
-- Spring Boot auto-configurations gate themselves with @Conditional* annotations
-- on the @Configuration class or its @Bean methods. The most common one is
-- @ConditionalOnClass — the auto-config only activates if a given class is on
-- the classpath. @ConditionalOnMissingClass is the opposite. There are also
-- bean-based, property-based, and web-application conditions, but class-based
-- is by far the most common.
--
-- jrdi extracts every @Conditional* from every auto-config class it sees
-- (V5 table gives us the class list; this table gives us the conditions).
-- The LLM uses this to predict which auto-configs will actually fire at
-- runtime, given the indexed classpath.

CREATE TABLE spring_autoconfig_conditions (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    autoconfig_class    TEXT    NOT NULL,
    condition_type      TEXT    NOT NULL,    -- "on_class" / "on_missing_class" / "on_bean" / "on_missing_bean" / "on_property" / "on_web_app" / "on_single_candidate" / "other"
    required_class      TEXT    NOT NULL DEFAULT '',  -- For on_class / on_missing_class
    required_bean_type  TEXT    NOT NULL DEFAULT '',  -- For on_bean / on_missing_bean
    required_property   TEXT    NOT NULL DEFAULT '',  -- For on_property
    -- on the auto-config class itself or on one of its @Bean methods:
    -- we record the source element so the LLM can disambiguate.
    -- Possible values: "class" / "bean:<methodName>"
    applied_to          TEXT    NOT NULL DEFAULT 'class',
    UNIQUE(autoconfig_class, condition_type, required_class, required_bean_type, required_property, applied_to)
);

CREATE INDEX idx_spring_autoconfig_conditions_class       ON spring_autoconfig_conditions(autoconfig_class);
CREATE INDEX idx_spring_autoconfig_conditions_required    ON spring_autoconfig_conditions(required_class);
CREATE INDEX idx_spring_autoconfig_conditions_type        ON spring_autoconfig_conditions(condition_type);
