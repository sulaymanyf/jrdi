package io.jrdi.storage.dialect;

import java.util.Locale;

public final class Dialects {

    private Dialects() {
    }

    public static Dialect detectFromJdbcUrl(String url) {
        if (url == null) {
            return SqliteDialect.INSTANCE;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:postgresql:") || lower.startsWith("jdbc:pg:")) {
            return PostgresDialect.INSTANCE;
        }
        if (lower.startsWith("jdbc:sqlite:") || lower.startsWith("jdbc:sqlite-")) {
            return SqliteDialect.INSTANCE;
        }
        throw new IllegalArgumentException("unsupported JDBC URL: " + url);
    }
}
