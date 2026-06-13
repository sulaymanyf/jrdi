package io.jrdi.storage;

import io.jrdi.storage.dialect.Dialect;
import io.jrdi.storage.dialect.Dialects;

import javax.sql.DataSource;
import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Thin factory that builds a {@link DataSource} for a JDBC URL and exposes the matching
 * {@link Dialect}. The class itself is {@link Closeable} — call {@link #close()} to release
 * the underlying HikariCP pool when needed.
 */
public final class Db implements Closeable {

    private final DataSource dataSource;
    private final Dialect dialect;
    private final String jdbcUrl;

    private Db(DataSource dataSource, Dialect dialect, String jdbcUrl) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.dialect = Objects.requireNonNull(dialect);
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl);
    }

    public static Db open(String jdbcUrl) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Dialect dialect = Dialects.detectFromJdbcUrl(jdbcUrl);
        DataSource ds = DataSources.build(jdbcUrl, dialect);
        return new Db(ds, dialect, jdbcUrl);
    }

    /**
     * Open a Db with explicit credentials. Useful for non-trivial PostgreSQL setups
     * where the URL alone doesn't carry the username/password.
     */
    public static Db open(String jdbcUrl, String username, String password) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Dialect dialect = Dialects.detectFromJdbcUrl(jdbcUrl);
        DataSource ds = DataSources.build(jdbcUrl, username, password, dialect);
        return new Db(ds, dialect, jdbcUrl);
    }

    public static Db inMemorySqlite() {
        return open("jdbc:sqlite::memory:");
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public Dialect dialect() {
        return dialect;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public Connection getConnection() throws SQLException {
        Connection c = dataSource.getConnection();
        if (dialect.name().equals("sqlite")) {
            try (var st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA journal_mode=WAL");
            }
        }
        return c;
    }

    @Override
    public void close() {
        DataSources.shutdown(dataSource);
    }
}
