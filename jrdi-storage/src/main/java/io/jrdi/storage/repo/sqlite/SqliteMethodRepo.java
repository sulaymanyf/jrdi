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
import io.jrdi.core.symbol.MethodKey;
import io.jrdi.storage.repo.MethodRepo;

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

public final class SqliteMethodRepo implements MethodRepo {

    private final DataSource ds;

    public SqliteMethodRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(long classId, String name, String desc, String signatureRaw,
                       Integer startLine, Integer endLine, boolean virtual) {
        String sql = """
                INSERT INTO methods(class_id, name, "desc", signature_raw, start_line, end_line, virtual_lines)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(class_id, name, "desc") DO UPDATE SET
                    signature_raw=COALESCE(excluded.signature_raw, methods.signature_raw),
                    start_line=COALESCE(excluded.start_line, methods.start_line),
                    end_line=COALESCE(excluded.end_line, methods.end_line),
                    virtual_lines=excluded.virtual_lines
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, classId);
            ps.setString(2, name);
            ps.setString(3, desc);
            if (signatureRaw == null) ps.setNull(4, Types.VARCHAR);
            else ps.setString(4, signatureRaw);
            if (startLine == null) ps.setNull(5, Types.INTEGER);
            else ps.setInt(5, startLine);
            if (endLine == null) ps.setNull(6, Types.INTEGER);
            else ps.setInt(6, endLine);
            ps.setInt(7, virtual ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            return -1L;
        } catch (SQLException e) {
            throw new RuntimeException("method upsert failed: " + name + desc, e);
        }
    }

    @Override
    public Optional<Record> findByKey(Fqn owner, MethodKey key) {
        String sql = """
                SELECT m.id, m.class_id, m.name, m."desc", m.signature_raw, m.start_line, m.end_line, m.virtual_lines
                FROM methods m JOIN classes c ON c.id = m.class_id
                WHERE c.fqn = ? AND m.name = ? AND m."desc" = ?
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner.slashed());
            ps.setString(2, key.name());
            ps.setString(3, key.descriptor());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("method findByKey failed", e);
        }
    }

    @Override
    public List<Record> findByClass(Fqn owner) {
        String sql = """
                SELECT m.id, m.class_id, m.name, m."desc", m.signature_raw, m.start_line, m.end_line, m.virtual_lines
                FROM methods m JOIN classes c ON c.id = m.class_id
                WHERE c.fqn = ?
                ORDER BY m.id
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner.slashed());
            try (ResultSet rs = ps.executeQuery()) {
                List<Record> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("method findByClass failed", e);
        }
    }

    private Record map(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        long classId = rs.getLong("class_id");
        String name = rs.getString("name");
        String desc = rs.getString("desc");
        String sig = rs.getString("signature_raw");
        int start = rs.getInt("start_line");
        Integer startLine = rs.wasNull() ? null : start;
        int end = rs.getInt("end_line");
        Integer endLine = rs.wasNull() ? null : end;
        boolean virtual = rs.getInt("virtual_lines") != 0;
        return new Record(id, classId, name, desc, sig, startLine, endLine, virtual);
    }
}
