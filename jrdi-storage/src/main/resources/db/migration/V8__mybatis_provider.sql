-- jrdi V8 schema: MyBatis @XxxProvider support.
--
-- MyBatis lets Mapper methods defer SQL generation to a runtime provider:
--   @SelectProvider(type = OwnerSqlProvider.class, method = "findByStatus")
--   String findByStatus(String status);
--
-- We can't extract the literal SQL at static-analysis time (it requires
-- running the provider method), so we record the binding: which Mapper
-- method uses which provider class+method. The LLM can then reason
-- "where does the SQL for this method come from?" and look at the
-- provider class to understand the SQL generation.

ALTER TABLE mybatis_statements ADD COLUMN provider_class TEXT NOT NULL DEFAULT '';
ALTER TABLE mybatis_statements ADD COLUMN provider_method TEXT NOT NULL DEFAULT '';

-- The provider_* columns are empty for direct-annotation and XML-discovered
-- statements. They're populated by MybatisPass when it sees a
-- @XxxProvider annotation on a Mapper method.
