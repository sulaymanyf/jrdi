/*
 * Copyright 2026 sulaymanyf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */package io.jrdi.storage;

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
