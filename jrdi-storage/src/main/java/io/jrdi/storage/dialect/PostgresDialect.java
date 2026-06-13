package io.jrdi.storage.dialect;

public final class PostgresDialect implements Dialect {

    public static final PostgresDialect INSTANCE = new PostgresDialect();

    private PostgresDialect() {
    }

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public String pingSql() {
        return "SELECT 1";
    }

    @Override
    public String jsonExtract(String column, String path) {
        return "(" + column + " ->> '" + path + "')";
    }
}
