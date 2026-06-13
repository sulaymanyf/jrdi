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
