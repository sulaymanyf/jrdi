-- jrdi V5 schema: Spring Boot auto-configuration discovery.
--
-- Spring Boot uses two file formats to register auto-configurations:
--   1. (Pre-3.0)   META-INF/spring.factories
--      Format:   Properties file with keys like
--                org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
--                com.acme.MyAutoConfig,com.acme.OtherAutoConfig
--   2. (3.0+)     META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
--      Format:   One FQN per line, comments start with #
--
-- We record every discovered entry with its source format so the LLM can
-- answer "what auto-configurations does this project pull in" and "is this
-- jar pre-3.0 (spring.factories) or 3.0+ (imports) style".

CREATE TABLE spring_boot_autoconfigs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    class_fqn       TEXT    NOT NULL,
    source_file     TEXT    NOT NULL,    -- e.g. "META-INF/spring.factories"
    source_format   TEXT    NOT NULL,    -- "factories" or "imports"
    key_in_factories TEXT   NOT NULL DEFAULT '',
    -- The key from spring.factories (e.g. EnableAutoConfiguration). Empty
    -- for .imports files which have no key concept.
    UNIQUE(class_fqn, source_file, source_format, key_in_factories)
);

CREATE INDEX idx_spring_autoconfigs_class   ON spring_boot_autoconfigs(class_fqn);
CREATE INDEX idx_spring_autoconfigs_format  ON spring_boot_autoconfigs(source_format);
CREATE INDEX idx_spring_autoconfigs_key     ON spring_boot_autoconfigs(key_in_factories);
