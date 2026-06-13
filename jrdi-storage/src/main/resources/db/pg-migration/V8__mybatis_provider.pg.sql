-- jrdi V8 schema (PostgreSQL): MyBatis @XxxProvider support.
-- Mirrors V8__mybatis_provider.sql in the SQLite dialect.

ALTER TABLE mybatis_statements ADD COLUMN provider_class TEXT NOT NULL DEFAULT '';
ALTER TABLE mybatis_statements ADD COLUMN provider_method TEXT NOT NULL DEFAULT '';
