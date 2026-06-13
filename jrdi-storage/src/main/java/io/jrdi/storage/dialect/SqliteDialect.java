package io.jrdi.storage.dialect;

public final class SqliteDialect implements Dialect {

    public static final SqliteDialect INSTANCE = new SqliteDialect();

    private SqliteDialect() {
    }

    @Override
    public String name() {
        return "sqlite";
    }

    @Override
    public String pingSql() {
        return "SELECT 1";
    }

    @Override
    public String jsonExtract(String column, String path) {
        return "json_extract(" + column + ", '$." + path + "')";
    }
}
