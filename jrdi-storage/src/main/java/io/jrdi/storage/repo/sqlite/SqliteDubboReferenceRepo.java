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

import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.repo.DubboReferenceRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class SqliteDubboReferenceRepo implements DubboReferenceRepo {

    private final DataSource ds;

    public SqliteDubboReferenceRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void record(Fqn interfaceFqn, long fieldId, String group, String version,
                       Confidence confidence, String refId, String registryId,
                       String consumerClassFqn) {
        String sql = """
                INSERT INTO dubbo_references(
                    interface_fqn, field_id, group_name, version, confidence, ref_id, registry_id,
                    consumer_class_fqn)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(interface_fqn, group_name, version, ref_id, field_id, registry_id, consumer_class_fqn)
                DO UPDATE SET
                    confidence = excluded.confidence
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, interfaceFqn.slashed());
            ps.setLong(2, fieldId);
            ps.setString(3, group == null ? "" : group);
            ps.setString(4, version == null ? "" : version);
            ps.setString(5, confidence.name());
            ps.setString(6, refId == null ? "" : refId);
            ps.setString(7, registryId == null ? "" : registryId);
            ps.setString(8, consumerClassFqn == null ? "" : consumerClassFqn);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("dubbo reference upsert failed", e);
        }
    }

    @Override
    public List<Record> findByField(long fieldId) {
        return query("WHERE field_id = ?", ps -> ps.setLong(1, fieldId));
    }

    @Override
    public List<Record> findByInterface(Fqn interfaceFqn) {
        return query("WHERE interface_fqn = ?", ps -> ps.setString(1, interfaceFqn.slashed()));
    }

    @Override
    public List<Record> findByConsumerClass(Fqn consumerClass) {
        return query("WHERE consumer_class_fqn = ?", ps -> ps.setString(1, consumerClass.slashed()));
    }

    @FunctionalInterface
    private interface ParamBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Record> query(String where, ParamBinder binder) {
        String sql = "SELECT id, interface_fqn, field_id, group_name, version, confidence, ref_id, " +
                "registry_id, consumer_class_fqn FROM dubbo_references " + where;
        List<Record> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(toRecord(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("dubbo reference query failed", e);
        }
        return out;
    }

    private static Record toRecord(ResultSet rs) throws SQLException {
        return new Record(
                rs.getLong("id"),
                Fqn.of(rs.getString("interface_fqn")),
                rs.getLong("field_id"),
                rs.getString("group_name"),
                rs.getString("version"),
                Confidence.valueOf(rs.getString("confidence")),
                rs.getString("ref_id"),
                rs.getString("registry_id"),
                rs.getString("consumer_class_fqn"));
    }
}
