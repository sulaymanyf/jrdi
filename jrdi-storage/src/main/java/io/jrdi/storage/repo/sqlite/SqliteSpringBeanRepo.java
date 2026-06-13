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
import io.jrdi.storage.repo.SpringBeanRepo;

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

public final class SqliteSpringBeanRepo implements SpringBeanRepo {

    private final DataSource ds;

    public SqliteSpringBeanRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(String name, Fqn typeFqn, String source, Long classId, Long methodId,
                       String scope, boolean primary) {
        String sql = """
                INSERT INTO spring_beans(name, type_fqn, source, class_id, method_id, scope, primary_b)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(name, type_fqn) DO UPDATE SET
                    source=excluded.source,
                    class_id=excluded.class_id,
                    method_id=excluded.method_id,
                    scope=excluded.scope,
                    primary_b=excluded.primary_b
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, typeFqn.slashed());
            ps.setString(3, source);
            if (classId == null) ps.setNull(4, Types.INTEGER); else ps.setLong(4, classId);
            if (methodId == null) ps.setNull(5, Types.INTEGER); else ps.setLong(5, methodId);
            ps.setString(6, scope == null ? "singleton" : scope);
            ps.setInt(7, primary ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            return findByNameAndType(name, typeFqn).map(Record::id).orElse(-1L);
        } catch (SQLException e) {
            throw new RuntimeException("spring bean upsert failed", e);
        }
    }

    @Override
    public List<Record> findByType(Fqn typeFqn) {
        return query("WHERE type_fqn = ?", typeFqn.slashed());
    }

    @Override
    public List<Record> findByName(String name) {
        return query("WHERE name = ?", name);
    }

    @Override
    public Optional<Record> findByNameAndType(String name, Fqn typeFqn) {
        List<Record> hits = query("WHERE name = ? AND type_fqn = ?", name, typeFqn.slashed());
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    @Override
    public List<Record> findAll() {
        return query("LIMIT 10000");
    }
    private List<Record> query(String where, Object... args) {
        String sql = "SELECT id, name, type_fqn, source, class_id, method_id, scope, primary_b FROM spring_beans " + where;
        List<Record> out = new ArrayList<>();
        long t0 = System.nanoTime();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a == null) {
                    ps.setNull(i + 1, Types.VARCHAR);
                } else {
                    ps.setString(i + 1, a.toString());
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long classIdRaw = rs.getLong("class_id");
                    Long classId = rs.wasNull() ? null : classIdRaw;
                    long methodIdRaw = rs.getLong("method_id");
                    Long methodId = rs.wasNull() ? null : methodIdRaw;
                    out.add(new Record(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("type_fqn"),
                            rs.getString("source"),
                            classId,
                            methodId,
                            rs.getString("scope"),
                            rs.getInt("primary_b") != 0));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("spring bean query failed: " + e.getMessage() + " (sql=" + sql + ")", e);
        }
        org.slf4j.LoggerFactory.getLogger(SqliteSpringBeanRepo.class)
                .debug("query({}) returned {} rows in {} ms", sql, out.size(),
                        (System.nanoTime() - t0) / 1_000_000);
        return out;
    }
}
