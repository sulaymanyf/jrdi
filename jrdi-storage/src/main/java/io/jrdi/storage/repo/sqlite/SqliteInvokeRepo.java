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

import io.jrdi.storage.repo.InvokeRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public final class SqliteInvokeRepo implements InvokeRepo {

    private final DataSource ds;

    public SqliteInvokeRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void insertAll(Iterable<Edge> edges) {
        String sql = """
                INSERT INTO invokes(caller_method_id, callee_owner, callee_name, callee_desc, kind, line, confidence)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            boolean prevAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                for (Edge e : edges) {
                    ps.setLong(1, e.callerMethodId());
                    ps.setString(2, e.calleeOwner());
                    ps.setString(3, e.calleeName());
                    ps.setString(4, e.calleeDesc());
                    ps.setString(5, e.kind().name());
                    if (e.line() == null) ps.setNull(6, Types.INTEGER);
                    else ps.setInt(6, e.line());
                    ps.setString(7, e.confidence().name());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(prevAuto);
            }
        } catch (SQLException e) {
            throw new RuntimeException("insert invokes failed", e);
        }
    }

    @Override
    public List<Edge> findCallersOf(String owner, String name, String desc) {
        String sql = """
                SELECT i.caller_method_id, i.callee_owner, i.callee_name, i.callee_desc, i.kind, i.line, i.confidence
                FROM invokes i
                JOIN methods m ON m.id = i.caller_method_id
                JOIN classes c ON c.id = m.class_id
                WHERE i.callee_owner = ? AND i.callee_name = ? AND i.callee_desc = ?
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setString(2, name);
            ps.setString(3, desc);
            try (ResultSet rs = ps.executeQuery()) {
                List<Edge> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("findCallersOf failed", e);
        }
    }

    @Override
    public List<Edge> findCalleesOf(long callerMethodId) {
        String sql = """
                SELECT caller_method_id, callee_owner, callee_name, callee_desc, kind, line, confidence
                FROM invokes WHERE caller_method_id = ?
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, callerMethodId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Edge> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("findCalleesOf failed", e);
        }
    }

    private Edge map(ResultSet rs) throws SQLException {
        long caller = rs.getLong("caller_method_id");
        String owner = rs.getString("callee_owner");
        String name = rs.getString("callee_name");
        String desc = rs.getString("callee_desc");
        Kind kind = Kind.valueOf(rs.getString("kind"));
        int line = rs.getInt("line");
        Integer lineBox = rs.wasNull() ? null : line;
        Confidence conf = Confidence.valueOf(rs.getString("confidence"));
        return new Edge(caller, owner, name, desc, kind, lineBox, conf);
    }
}
