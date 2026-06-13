package io.jrdi.storage.dialect;

/**
 * SQL dialect glue for tiny differences that cannot be expressed via JDBC types.
 * The goal is to keep SQL files ANSI-portable; the dialect only covers identity
 * and a couple of helpers.
 */
public interface Dialect {

    String name();

    /** Identity query for connection validation. */
    String pingSql();

    /** JSON1 expression name (SQLite: {@code json_extract}, PG: {@code ...}). */
    String jsonExtract(String column, String path);
}
