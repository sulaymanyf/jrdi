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
 */package io.jrdi.mybatis;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.MybatisStatementRepo;
import io.jrdi.storage.repo.MybatisStatementRepo.Kind;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisPassTest {

    private Db db;

    @BeforeEach
    void setUp() {
        db = Db.inMemorySqlite();
        Migrator.migrate(db.dataSource());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void select_annotation_records_statement() {
        String src = """
                package com.acme.mapper;
                import org.apache.ibatis.annotations.Select;
                public interface OwnerMapper {
                    @Select("SELECT id, name FROM owner WHERE name = #{name}")
                    String findByName(String name);
                }
                """;
        var r = new MybatisPass(db).scan(src, Fqn.fromDotted("com.acme.mapper.OwnerMapper"));
        assertThat(r.statementsRecorded()).isEqualTo(1);
        List<MybatisStatementRepo.Record> stmts =
                SqliteRepos.mybatisStatementRepo(db).findByNamespace("com.acme.mapper.OwnerMapper");
        assertThat(stmts).hasSize(1);
        assertThat(stmts.get(0).kind()).isEqualTo(Kind.SELECT);
        assertThat(stmts.get(0).statementId()).isEqualTo("findByName");
        assertThat(stmts.get(0).sqlTemplate()).contains("SELECT id, name FROM owner");
    }

    @Test
    void insert_annotation_normalizes_whitespace() {
        String src = """
                package com.acme.mapper;
                import org.apache.ibatis.annotations.Insert;
                public interface LogMapper {
                    @Insert("INSERT INTO log (msg) VALUES (#{msg})")
                    int insert(String msg);
                }
                """;
        new MybatisPass(db).scan(src, Fqn.fromDotted("com.acme.mapper.LogMapper"));
        var stmt = SqliteRepos.mybatisStatementRepo(db)
                .findByNamespaceAndId("com.acme.mapper.LogMapper", "insert").get(0);
        // Comments stripped, whitespace collapsed
        assertThat(stmt.sqlNormalized()).isEqualTo("INSERT INTO log (msg) VALUES (#{msg})");
        assertThat(stmt.kind()).isEqualTo(Kind.INSERT);
    }

    @Test
    void select_provider_records_binding_with_no_sql_template() {
        String src = """
                package com.acme.mapper;
                import org.apache.ibatis.annotations.SelectProvider;
                public interface OwnerMapper {
                    @SelectProvider(type = com.acme.mapper.OwnerSqlProvider.class, method = "findByName")
                    String findByName(String name);
                }
                """;
        var r = new MybatisPass(db).scan(src, Fqn.fromDotted("com.acme.mapper.OwnerMapper"));
        assertThat(r.statementsRecorded()).isEqualTo(1);
        var stmt = SqliteRepos.mybatisStatementRepo(db)
                .findByNamespaceAndId("com.acme.mapper.OwnerMapper", "findByName").get(0);
        // The SQL is generated at runtime; we only record the binding.
        assertThat(stmt.sqlTemplate()).isEmpty();
        assertThat(stmt.sqlNormalized()).isEmpty();
        assertThat(stmt.providerClass()).isEqualTo("com.acme.mapper.OwnerSqlProvider");
        assertThat(stmt.providerMethod()).isEqualTo("findByName");
        assertThat(stmt.kind()).isEqualTo(Kind.SELECT);
    }

    @Test
    void update_provider_records_binding() {
        String src = """
                package com.acme.mapper;
                import org.apache.ibatis.annotations.UpdateProvider;
                public interface OrderMapper {
                    @UpdateProvider(type = com.acme.mapper.OrderSqlProvider.class, method = "updateStatus")
                    int updateStatus(long id, String status);
                }
                """;
        new MybatisPass(db).scan(src, Fqn.fromDotted("com.acme.mapper.OrderMapper"));
        var stmt = SqliteRepos.mybatisStatementRepo(db)
                .findByNamespaceAndId("com.acme.mapper.OrderMapper", "updateStatus").get(0);
        assertThat(stmt.providerClass()).isEqualTo("com.acme.mapper.OrderSqlProvider");
        assertThat(stmt.providerMethod()).isEqualTo("updateStatus");
        assertThat(stmt.kind()).isEqualTo(Kind.UPDATE);
    }
}
