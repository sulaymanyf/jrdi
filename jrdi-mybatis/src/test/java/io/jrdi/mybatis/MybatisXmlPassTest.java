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

import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.MybatisStatementRepo;
import io.jrdi.storage.repo.MybatisStatementRepo.Kind;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisXmlPassTest {

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
    void parses_select_insert_update_delete(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.acme.mapper.OwnerMapper">
                    <select id="findById" resultType="com.acme.Owner">
                        SELECT id, name FROM owner WHERE id = #{id}
                    </select>
                    <insert id="insert" parameterType="com.acme.Owner">
                        INSERT INTO owner (name) VALUES (#{name})
                    </insert>
                    <update id="update" parameterType="com.acme.Owner">
                        UPDATE owner SET name = #{name} WHERE id = #{id}
                    </update>
                    <delete id="delete" parameterType="long">
                        DELETE FROM owner WHERE id = #{id}
                    </delete>
                </mapper>
                """;
        Path xmlFile = tmp.resolve("OwnerMapper.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);

        var r = new MybatisXmlPass(db).scanFile(xmlFile);
        assertThat(r.statementsRecorded()).isEqualTo(4);
        assertThat(r.filesScanned()).isEqualTo(1);

        MybatisStatementRepo repo = SqliteRepos.mybatisStatementRepo(db);
        var stmts = repo.findByNamespace("com.acme.mapper.OwnerMapper");
        assertThat(stmts).hasSize(4);
        // Each statement's template should mention the SQL keyword matching its kind.
        for (var s : stmts) {
            String kw = switch (s.kind()) {
                case SELECT -> "SELECT";
                case INSERT -> "INSERT";
                case UPDATE -> "UPDATE";
                case DELETE -> "DELETE";
            };
            assertThat(s.sqlTemplate()).contains(kw);
        }
        // Check the select's parameters
        var sel = repo.findByNamespaceAndId("com.acme.mapper.OwnerMapper", "findById").get(0);
        assertThat(sel.kind()).isEqualTo(Kind.SELECT);
        assertThat(sel.parameters()).containsExactly("id");
        // Check the update's parameters
        var upd = repo.findByNamespaceAndId("com.acme.mapper.OwnerMapper", "update").get(0);
        assertThat(upd.kind()).isEqualTo(Kind.UPDATE);
        assertThat(upd.parameters()).containsExactly("name", "id");
    }

    @Test
    void inlines_sql_fragments(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.acme.mapper.UserMapper">
                    <sql id="columns">id, name, email</sql>
                    <select id="findById" resultType="com.acme.User">
                        SELECT <include refid="columns"/> FROM user WHERE id = #{id}
                    </select>
                </mapper>
                """;
        Path xmlFile = tmp.resolve("UserMapper.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);

        new MybatisXmlPass(db).scanFile(xmlFile);
        var stmt = SqliteRepos.mybatisStatementRepo(db)
                .findByNamespaceAndId("com.acme.mapper.UserMapper", "findById").get(0);
        // The inlined template should have the columns, not the <include/> tag.
        assertThat(stmt.sqlTemplate()).contains("id, name, email");
        // The literal "<include " (with the trailing space and full tag) should be
        // replaced. We check for the actual <include/> form, not the comment marker
        // text (which contains "<include" as part of its inlining notice).
        assertThat(stmt.sqlTemplate()).doesNotContain("<include ");
    }

    @Test
    void elides_dynamic_tags_but_keeps_inner_text(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.acme.mapper.OrderMapper">
                    <select id="search" resultType="com.acme.Order">
                        SELECT id FROM orders
                        <where>
                            <if test="status != null">AND status = #{status}</if>
                            <if test="minTotal != null">AND total >= #{minTotal}</if>
                        </where>
                    </select>
                </mapper>
                """;
        Path xmlFile = tmp.resolve("OrderMapper.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new MybatisXmlPass(db).scanFile(xmlFile);
        var stmt = SqliteRepos.mybatisStatementRepo(db)
                .findByNamespaceAndId("com.acme.mapper.OrderMapper", "search").get(0);
        // The raw template should still contain the dynamic tags (so the LLM sees them).
        assertThat(stmt.sqlTemplate()).contains("<if");
        assertThat(stmt.sqlTemplate()).contains("<where");
        // The normalised view should have the inner SQL text and a parameter for each.
        // JSqlParser keeps #{...} placeholders in the normalised output (it does
        // not replace them with "?"; that's a runtime binding).
        assertThat(stmt.sqlNormalized()).contains("SELECT id FROM orders");
        assertThat(stmt.sqlNormalized()).contains("status = #{status}");
        assertThat(stmt.sqlNormalized()).contains("total >= #{minTotal}");
        assertThat(stmt.parameters()).contains("status", "minTotal");
    }

    @Test
    void reads_mapper_from_jar(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.acme.mapper.JarMapper">
                    <select id="ping" resultType="int">SELECT 1</select>
                </mapper>
                """;
        Path jar = tmp.resolve("acme-mybatis-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("com/acme/mapper/JarMapper.xml"));
            z.write(xml.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            // Sprinkle a non-mapper xml to verify it's ignored
            z.putNextEntry(new ZipEntry("logback.xml"));
            z.write("<configuration/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        var r = new MybatisXmlPass(db).scanJar(jar);
        assertThat(r.statementsRecorded()).isEqualTo(1);
        assertThat(r.filesScanned()).isEqualTo(1);

        var stmt = SqliteRepos.mybatisStatementRepo(db)
                .findByNamespaceAndId("com.acme.mapper.JarMapper", "ping").get(0);
        assertThat(stmt.kind()).isEqualTo(Kind.SELECT);
        assertThat(stmt.sqlTemplate()).contains("SELECT 1");
    }

    @Test
    void missing_namespace_is_skipped(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper>
                    <select id="orphan" resultType="int">SELECT 1</select>
                </mapper>
                """;
        Path xmlFile = tmp.resolve("OrphanMapper.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        var r = new MybatisXmlPass(db).scanFile(xmlFile);
        assertThat(r.statementsRecorded()).isZero();
    }

    @Test
    void scan_is_idempotent(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.acme.mapper.IdemMapper">
                    <select id="ping" resultType="int">SELECT 1</select>
                </mapper>
                """;
        Path xmlFile = tmp.resolve("IdemMapper.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        var pass = new MybatisXmlPass(db);
        pass.scanFile(xmlFile);
        pass.scanFile(xmlFile);
        pass.scanFile(xmlFile);
        // V3 ON CONFLICT: only one row survives.
        var stmts = SqliteRepos.mybatisStatementRepo(db)
                .findByNamespace("com.acme.mapper.IdemMapper");
        assertThat(stmts).hasSize(1);
    }

    @Test
    void captures_dollar_and_hash_parameters(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.acme.mapper.MixedParamMapper">
                    <select id="search" resultType="map">
                        SELECT * FROM ${table} WHERE col = #{value} AND col2 = #{value}
                    </select>
                </mapper>
                """;
        Path xmlFile = tmp.resolve("MixedParamMapper.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new MybatisXmlPass(db).scanFile(xmlFile);
        var stmt = SqliteRepos.mybatisStatementRepo(db)
                .findByNamespaceAndId("com.acme.mapper.MixedParamMapper", "search").get(0);
        // Adjacent duplicates are deduped; "table" and "value" are both captured.
        assertThat(stmt.parameters()).containsExactly("table", "value");
    }
}
