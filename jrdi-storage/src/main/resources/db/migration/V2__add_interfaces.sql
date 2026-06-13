-- jrdi V2 schema: track implemented interfaces per class.
-- Used by jrdi-spring's @Autowired candidate resolution: a bean's type might
-- implement the field type (interface or abstract class) without being a
-- direct match.

ALTER TABLE classes ADD COLUMN interfaces TEXT NOT NULL DEFAULT '';
