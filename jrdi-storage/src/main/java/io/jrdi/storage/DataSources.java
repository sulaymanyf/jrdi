package io.jrdi.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jrdi.storage.dialect.Dialect;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

final class DataSources {

    private static final Map<DataSource, HikariDataSource> POOL = new HashMap<>();

    private DataSources() {
    }

    static DataSource build(String jdbcUrl, Dialect dialect) {
        return build(jdbcUrl, null, null, dialect);
    }

    static DataSource build(String jdbcUrl, String username, String password, Dialect dialect) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (username != null) config.setUsername(username);
        if (password != null) config.setPassword(password);
        config.setPoolName("jrdi-" + dialect.name());
        if (dialect.name().equals("sqlite")) {
            config.setMaximumPoolSize(4);
            config.setMinimumIdle(1);
            config.setConnectionTestQuery("SELECT 1");
        } else if (dialect.name().equals("postgresql")) {
            config.setMaximumPoolSize(8);
            config.setMinimumIdle(2);
        }
        HikariDataSource ds = new HikariDataSource(config);
        POOL.put(ds, ds);
        return ds;
    }

    static void shutdown(DataSource ds) {
        HikariDataSource pool = POOL.remove(ds);
        if (pool != null) {
            pool.close();
        }
    }
}
