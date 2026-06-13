package io.jrdi.storage;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Wraps Flyway to apply jrdi's classpath migrations to a {@link DataSource}.
 * No-op (returns immediately) if {@code dataSource} is null.
 */
public final class Migrator {

    private static final Logger LOG = LoggerFactory.getLogger(Migrator.class);

    private Migrator() {
    }

    public static void migrate(DataSource dataSource) {
        String url;
        try (var c = dataSource.getConnection()) {
            url = c.getMetaData().getURL();
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Migrator: cannot read URL from DataSource", e);
        }
        String location = locationFor(url);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(location)
                .baselineOnMigrate(true)
                .load();
        var result = flyway.migrate();
        LOG.info("flyway applied {} migration(s), schema at version {}",
                result.migrationsExecuted, result.targetSchemaVersion);
    }

    /**
     * Drop all jrdi V1 objects in the database. For SQLite file-based DBs this is
     * a no-op when the file is later deleted by the caller. For PostgreSQL we drop
     * the schema namespace. Safe to call against a fresh DB (no-op if nothing to drop).
     */
    public static void clean(DataSource dataSource) {
        String url;
        try (var c = dataSource.getConnection()) {
            url = c.getMetaData().getURL();
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Migrator: cannot read URL from DataSource", e);
        }
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(locationFor(url))
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        LOG.info("flyway clean: schema dropped");
    }

    /**
     * Pick the migration directory based on the JDBC URL.
     * Anything that starts with {@code jdbc:postgresql:} uses the PG dialect.
     */
    static String locationFor(String url) {
        if (url != null && url.toLowerCase().startsWith("jdbc:postgresql:")) {
            return "classpath:db/pg-migration";
        }
        return "classpath:db/migration";
    }
}
