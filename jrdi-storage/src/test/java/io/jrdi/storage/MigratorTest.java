package io.jrdi.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigratorTest {

    @Test
    void detects_postgres() {
        assertThat(Migrator.locationFor("jdbc:postgresql://localhost/jrdi"))
                .isEqualTo("classpath:db/pg-migration");
        assertThat(Migrator.locationFor("jdbc:postgresql://localhost/jrdi?user=foo&password=bar"))
                .isEqualTo("classpath:db/pg-migration");
    }

    @Test
    void detects_postgres_case_insensitive() {
        assertThat(Migrator.locationFor("jdbc:PostgreSQL://localhost/jrdi"))
                .isEqualTo("classpath:db/pg-migration");
    }

    @Test
    void falls_back_to_sqlite() {
        assertThat(Migrator.locationFor("jdbc:sqlite::memory:"))
                .isEqualTo("classpath:db/migration");
        assertThat(Migrator.locationFor("jdbc:sqlite:/tmp/jrdi.db"))
                .isEqualTo("classpath:db/migration");
    }

    @Test
    void null_url_is_sqlite() {
        assertThat(Migrator.locationFor(null))
                .isEqualTo("classpath:db/migration");
    }
}
