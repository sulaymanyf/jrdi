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

import io.jrdi.storage.repo.DubboRegistryRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteDubboRegistryRepo implements DubboRegistryRepo {

    private final DataSource ds;

    public SqliteDubboRegistryRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(String registryId, String address, String protocol, Integer port,
                       String username, String parameters, String sourceFile) {
        String sql = """
                INSERT INTO dubbo_registries(
                    registry_id, address, protocol, port, username, parameters, source_file)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(source_file, registry_id)
                DO UPDATE SET
                    address    = excluded.address,
                    protocol   = excluded.protocol,
                    port       = excluded.port,
                    username   = excluded.username,
                    parameters = excluded.parameters
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, registryId == null ? "" : registryId);
            ps.setString(2, address == null ? "" : address);
            ps.setString(3, protocol == null ? "" : protocol);
            if (port == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, port);
            ps.setString(5, username == null ? "" : username);
            ps.setString(6, parameters == null ? "{}" : parameters);
            ps.setString(7, sourceFile == null ? "" : sourceFile);
            int affected = ps.executeUpdate();
            if (affected == 1) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
            return findIdOnConnection(c, registryId, sourceFile);
        } catch (SQLException e) {
            throw new RuntimeException("dubbo registry upsert failed", e);
        }
    }

    private long findIdOnConnection(Connection c, String registryId, String sourceFile) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM dubbo_registries WHERE registry_id=? AND source_file=?")) {
            ps.setString(1, registryId == null ? "" : registryId);
            ps.setString(2, sourceFile == null ? "" : sourceFile);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1L;
    }

    @Override
    public Optional<Record> findByRegistryId(String registryId) {
        var list = query("WHERE registry_id=?", registryId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<Record> findByProtocol(String protocol) {
        return query("WHERE protocol=?", protocol);
    }

    @Override
    public List<Record> findAll() {
        return query("LIMIT 1000");
    }

    private List<Record> query(String where, Object... args) {
        String sql = "SELECT id, registry_id, address, protocol, port, username, parameters, source_file " +
                "FROM dubbo_registries " + where;
        List<Record> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int portRaw = rs.getInt("port");
                    Integer port = rs.wasNull() ? null : portRaw;
                    out.add(new Record(
                            rs.getLong("id"),
                            rs.getString("registry_id"),
                            rs.getString("address"),
                            rs.getString("protocol"),
                            port,
                            rs.getString("username"),
                            rs.getString("parameters"),
                            rs.getString("source_file")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("dubbo registry query failed", e);
        }
        return out;
    }
}
