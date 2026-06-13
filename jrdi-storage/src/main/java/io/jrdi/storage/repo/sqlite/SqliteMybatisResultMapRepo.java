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
 */package io.jrdi.storage.repo.sqlite;

import io.jrdi.storage.repo.MybatisResultMapRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class SqliteMybatisResultMapRepo implements MybatisResultMapRepo {

    private final DataSource ds;

    public SqliteMybatisResultMapRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(String namespace, String mapId, String typeFqn, String extendsRef,
                       boolean autoMapping, int propertyCount,
                       int associationCount, int collectionCount) {
        String sql = """
                INSERT INTO mybatis_result_maps(
                    namespace, map_id, type_fqn, extends_,
                    auto_mapping, property_count, association_count, collection_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(namespace, map_id)
                DO UPDATE SET
                    type_fqn          = excluded.type_fqn,
                    extends_          = excluded.extends_,
                    auto_mapping      = excluded.auto_mapping,
                    property_count    = excluded.property_count,
                    association_count = excluded.association_count,
                    collection_count  = excluded.collection_count
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, namespace);
            ps.setString(2, mapId);
            ps.setString(3, typeFqn == null ? "" : typeFqn);
            ps.setString(4, extendsRef == null ? "" : extendsRef);
            ps.setBoolean(5, autoMapping);
            ps.setInt(6, propertyCount);
            ps.setInt(7, associationCount);
            ps.setInt(8, collectionCount);
            int affected = ps.executeUpdate();
            if (affected == 1) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
            return findIdOnConnection(c, namespace, mapId);
        } catch (SQLException e) {
            throw new RuntimeException("mybatis result map upsert failed", e);
        }
    }

    private long findIdOnConnection(Connection c, String namespace, String mapId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM mybatis_result_maps WHERE namespace=? AND map_id=?")) {
            ps.setString(1, namespace);
            ps.setString(2, mapId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1L;
    }

    @Override
    public List<Record> findByNamespace(String namespace) {
        return query("WHERE namespace=?", namespace);
    }

    @Override
    public List<Record> findByType(String typeFqn) {
        return query("WHERE type_fqn=?", typeFqn);
    }

    private List<Record> query(String where, Object... args) {
        String sql = "SELECT id, namespace, map_id, type_fqn, extends_, auto_mapping, " +
                "property_count, association_count, collection_count " +
                "FROM mybatis_result_maps " + where;
        List<Record> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Record(
                            rs.getLong("id"),
                            rs.getString("namespace"),
                            rs.getString("map_id"),
                            rs.getString("type_fqn"),
                            rs.getString("extends_"),
                            rs.getBoolean("auto_mapping"),
                            rs.getInt("property_count"),
                            rs.getInt("association_count"),
                            rs.getInt("collection_count")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("mybatis result map query failed", e);
        }
        return out;
    }
}
