-- V11 (PG): classes.source_jar
ALTER TABLE classes ADD COLUMN source_jar TEXT NOT NULL DEFAULT '';
CREATE INDEX idx_classes_source_jar ON classes(source_jar);
