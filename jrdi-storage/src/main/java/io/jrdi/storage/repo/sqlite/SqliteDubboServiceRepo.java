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
import io.jrdi.storage.repo.DubboServiceRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class SqliteDubboServiceRepo implements DubboServiceRepo {

    private final DataSource ds;

    public SqliteDubboServiceRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(Fqn interfaceFqn, long implClassId, String group, String version,
                       String protocol, String source, String refBeanName, String registryId) {
        String sql = """
                INSERT INTO dubbo_services(
                    interface_fqn, impl_class_id, group_name, version, protocol, source,
                    ref_bean_name, registry_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(interface_fqn, group_name, version, ref_bean_name, impl_class_id, registry_id)
                DO UPDATE SET
                    protocol = excluded.protocol,
                    source   = excluded.source
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, interfaceFqn.slashed());
            ps.setLong(2, implClassId);
            ps.setString(3, group == null ? "" : group);
            ps.setString(4, version == null ? "" : version);
            ps.setString(5, protocol == null ? "dubbo" : protocol);
            ps.setString(6, source);
            ps.setString(7, refBeanName == null ? "" : refBeanName);
            ps.setString(8, registryId == null ? "" : registryId);
            int affected = ps.executeUpdate();
            if (affected == 1) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
            return findIdOnConnection(c, interfaceFqn, group, version, refBeanName, implClassId, registryId);
        } catch (SQLException e) {
            throw new RuntimeException("dubbo service upsert failed", e);
        }
    }

    private long findIdOnConnection(Connection c, Fqn interfaceFqn, String group, String version,
                                    String refBeanName, long implClassId, String registryId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM dubbo_services " +
                "WHERE interface_fqn=? AND group_name=? AND version=? " +
                "AND ref_bean_name=? AND impl_class_id=? AND registry_id=?")) {
            ps.setString(1, interfaceFqn.slashed());
            ps.setString(2, group == null ? "" : group);
            ps.setString(3, version == null ? "" : version);
            ps.setString(4, refBeanName == null ? "" : refBeanName);
            ps.setLong(5, implClassId);
            ps.setString(6, registryId == null ? "" : registryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1L;
    }

    @Override
    public List<Record> findByInterface(Fqn interfaceFqn) {
        String sql = "SELECT id, interface_fqn, impl_class_id, group_name, version, protocol, source, " +
                "ref_bean_name, registry_id FROM dubbo_services WHERE interface_fqn = ?";
        List<Record> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, interfaceFqn.slashed());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Record(
                            rs.getLong("id"),
                            Fqn.of(rs.getString("interface_fqn")),
                            rs.getLong("impl_class_id"),
                            rs.getString("group_name"),
                            rs.getString("version"),
                            rs.getString("protocol"),
                            rs.getString("source"),
                            rs.getString("ref_bean_name"),
                            rs.getString("registry_id")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("dubbo service query failed", e);
        }
        return out;
    }
}
