-- jrdi V2 schema (PostgreSQL): track implemented interfaces per class.

ALTER TABLE classes ADD COLUMN interfaces TEXT NOT NULL DEFAULT '';
