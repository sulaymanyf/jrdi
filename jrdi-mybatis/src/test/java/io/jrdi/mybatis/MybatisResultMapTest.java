package io.jrdi.mybatis;

import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.MybatisResultMapRepo;
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

import static org.assertj.core.api.Assertions.assertThat;

class MybatisResultMapTest {

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
    void parses_flat_result_map(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.acme.mapper.UserMapper">
                    <resultMap id="userResult" type="com.acme.User">
                        <id property="id" column="id"/>
                        <result property="name" column="user_name"/>
                        <result property="email" column="user_email"/>
                    </resultMap>
                    <select id="findById" resultMap="userResult">
                        SELECT * FROM user WHERE id = #{id}
                    </select>
                </mapper>
                """;
        Path xmlFile = tmp.resolve("UserMapper.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        var r = new MybatisXmlPass(db).scanFile(xmlFile);
        assertThat(r.resultMapsRecorded()).isEqualTo(1);
        assertThat(r.statementsRecorded()).isEqualTo(1);

        MybatisResultMapRepo repo = SqliteRepos.mybatisResultMapRepo(db);
        var maps = repo.findByNamespace("com.acme.mapper.UserMapper");
        assertThat(maps).hasSize(1);
        var m = maps.get(0);
        assertThat(m.mapId()).isEqualTo("userResult");
        assertThat(m.typeFqn()).isEqualTo("com.acme.User");
        assertThat(m.propertyCount()).isEqualTo(3);  // 1 id + 2 result
        assertThat(m.associationCount()).isZero();
        assertThat(m.collectionCount()).isZero();
    }

    @Test
    void parses_result_map_with_associations_and_collections(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.acme.mapper.OrderMapper">
                    <resultMap id="orderResult" type="com.acme.Order">
                        <id property="id" column="id"/>
                        <result property="total" column="total"/>
                        <association property="customer" javaType="com.acme.Customer">
                            <id property="id" column="customer_id"/>
                        </association>
                        <collection property="items" ofType="com.acme.Item">
                            <id property="id" column="item_id"/>
                            <id property="qty" column="qty"/>
                        </collection>
                    </resultMap>
                </mapper>
                """;
        Path xmlFile = tmp.resolve("OrderMapper.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new MybatisXmlPass(db).scanFile(xmlFile);

        var m = SqliteRepos.mybatisResultMapRepo(db)
                .findByNamespace("com.acme.mapper.OrderMapper").get(0);
        assertThat(m.propertyCount()).isEqualTo(2);   // 1 id + 1 result
        assertThat(m.associationCount()).isEqualTo(1);
        assertThat(m.collectionCount()).isEqualTo(1);
    }

    @Test
    void parses_extends_attribute(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.acme.mapper.BaseMapper">
                    <resultMap id="baseResult" type="com.acme.Base"/>
                    <resultMap id="derivedResult" type="com.acme.Derived" extends="baseResult">
                        <result property="extra" column="extra"/>
                    </resultMap>
                </mapper>
                """;
        Path xmlFile = tmp.resolve("BaseMapper.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new MybatisXmlPass(db).scanFile(xmlFile);

        var maps = SqliteRepos.mybatisResultMapRepo(db)
                .findByNamespace("com.acme.mapper.BaseMapper");
        assertThat(maps).hasSize(2);
        var derived = maps.stream().filter(m -> m.mapId().equals("derivedResult"))
                .findFirst().orElseThrow();
        assertThat(derived.extendsRef()).isEqualTo("baseResult");
        assertThat(derived.typeFqn()).isEqualTo("com.acme.Derived");
    }

    @Test
    void auto_mapping_attribute_is_captured(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.acme.mapper.AMMapper">
                    <resultMap id="autoMapped" type="com.acme.Foo" autoMapping="false"/>
                    <resultMap id="defaultMapped" type="com.acme.Bar"/>
                </mapper>
                """;
        Path xmlFile = tmp.resolve("AMMapper.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new MybatisXmlPass(db).scanFile(xmlFile);

        var maps = SqliteRepos.mybatisResultMapRepo(db)
                .findByNamespace("com.acme.mapper.AMMapper");
        var autoMapped = maps.stream().filter(m -> m.mapId().equals("autoMapped"))
                .findFirst().orElseThrow();
        var defaultMapped = maps.stream().filter(m -> m.mapId().equals("defaultMapped"))
                .findFirst().orElseThrow();
        assertThat(autoMapped.autoMapping()).isFalse();
        assertThat(defaultMapped.autoMapping()).isTrue();  // default
    }

    @Test
    void missing_id_attribute_is_skipped(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.acme.mapper.OrphanMapper">
                    <resultMap type="com.acme.Orphan"/>
                </mapper>
                """;
        Path xmlFile = tmp.resolve("OrphanMapper.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        var r = new MybatisXmlPass(db).scanFile(xmlFile);
        assertThat(r.resultMapsRecorded()).isZero();
    }

    @Test
    void find_by_type_fqn(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.acme.mapper.M1">
                    <resultMap id="r1" type="com.acme.Shared"/>
                </mapper>
                """;
        Path xmlFile = tmp.resolve("M1.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new MybatisXmlPass(db).scanFile(xmlFile);

        List<MybatisResultMapRepo.Record> byType = SqliteRepos.mybatisResultMapRepo(db)
                .findByType("com.acme.Shared");
        assertThat(byType).hasSize(1);
        assertThat(byType.get(0).mapId()).isEqualTo("r1");
    }
}
