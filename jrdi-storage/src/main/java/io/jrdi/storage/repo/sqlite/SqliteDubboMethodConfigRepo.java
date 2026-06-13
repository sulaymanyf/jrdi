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

import io.jrdi.storage.repo.DubboMethodConfigRepo;

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

public final class SqliteDubboMethodConfigRepo implements DubboMethodConfigRepo {

    private final DataSource ds;

    public SqliteDubboMethodConfigRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(long serviceId, long referenceId, String methodName,
                       Integer timeoutMs, Integer retries, String loadbalance,
                       boolean async, boolean sent) {
        String sql = """
                INSERT INTO dubbo_method_configs(
                    service_id, reference_id, method_name,
                    timeout_ms, retries, loadbalance, async, sent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(service_id, reference_id, method_name)
                DO UPDATE SET
                    timeout_ms  = excluded.timeout_ms,
                    retries     = excluded.retries,
                    loadbalance = excluded.loadbalance,
                    async       = excluded.async,
                    sent        = excluded.sent
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, serviceId);
            ps.setLong(2, referenceId);
            ps.setString(3, methodName);
            if (timeoutMs == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, timeoutMs);
            if (retries == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, retries);
            if (loadbalance == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, loadbalance);
            ps.setBoolean(7, async);
            ps.setBoolean(8, sent);
            int affected = ps.executeUpdate();
            if (affected == 1) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
            return findIdOnConnection(c, serviceId, referenceId, methodName);
        } catch (SQLException e) {
            throw new RuntimeException("dubbo method config upsert failed", e);
        }
    }

    private long findIdOnConnection(Connection c, long serviceId, long referenceId,
                                    String methodName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM dubbo_method_configs " +
                "WHERE service_id=? AND reference_id=? AND method_name=?")) {
            ps.setLong(1, serviceId);
            ps.setLong(2, referenceId);
            ps.setString(3, methodName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1L;
    }

    @Override
    public List<Record> findByService(long serviceId) {
        return query("WHERE service_id=?", serviceId);
    }

    @Override
    public List<Record> findByReference(long referenceId) {
        return query("WHERE reference_id=?", referenceId);
    }

    @Override
    public Optional<Record> findByServiceAndMethod(long serviceId, String methodName) {
        var list = query("WHERE service_id=? AND method_name=?", serviceId, methodName);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private List<Record> query(String where, Object... args) {
        String sql = "SELECT id, service_id, reference_id, method_name, " +
                "timeout_ms, retries, loadbalance, async, sent " +
                "FROM dubbo_method_configs " + where;
        List<Record> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(toRecord(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("dubbo method config query failed", e);
        }
        return out;
    }

    private static Record toRecord(ResultSet rs) throws SQLException {
        int timeoutRaw = rs.getInt("timeout_ms");
        Integer timeout = rs.wasNull() ? null : timeoutRaw;
        int retriesRaw = rs.getInt("retries");
        Integer retries = rs.wasNull() ? null : retriesRaw;
        return new Record(
                rs.getLong("id"),
                rs.getLong("service_id"),
                rs.getLong("reference_id"),
                rs.getString("method_name"),
                timeout,
                retries,
                rs.getString("loadbalance"),
                rs.getBoolean("async"),
                rs.getBoolean("sent"));
    }
}
