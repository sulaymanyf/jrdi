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
import io.jrdi.storage.repo.ClassRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class SqliteClassRepo implements ClassRepo {

    private final DataSource ds;

    public SqliteClassRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(Fqn fqn, int access, Fqn superFqn, Long fileId, String signatureRaw, String source) {
        return upsert(fqn, access, superFqn, fileId, signatureRaw, source, List.of());
    }

    @Override
    public long upsert(Fqn fqn, int access, Fqn superFqn, Long fileId, String signatureRaw, String source,
                       List<Fqn> interfaces) {
        String sql = """
                INSERT INTO classes(fqn, access, super_fqn, file_id, signature_raw, source, interfaces)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(fqn) DO UPDATE SET
                    access=excluded.access,
                    super_fqn=excluded.super_fqn,
                    file_id=COALESCE(excluded.file_id, classes.file_id),
                    signature_raw=COALESCE(excluded.signature_raw, classes.signature_raw),
                    source=excluded.source,
                    interfaces=excluded.interfaces
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, fqn.slashed());
            ps.setInt(2, access);
            if (superFqn == null) ps.setNull(3, Types.VARCHAR);
            else ps.setString(3, superFqn.slashed());
            if (fileId == null) ps.setNull(4, Types.INTEGER);
            else ps.setLong(4, fileId);
            if (signatureRaw == null) ps.setNull(5, Types.VARCHAR);
            else ps.setString(5, signatureRaw);
            ps.setString(6, source == null ? "jar" : source);
            ps.setString(7, encodeInterfaces(interfaces));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            return findByFqn(fqn).map(Record::id).orElse(-1L);
        } catch (SQLException e) {
            throw new RuntimeException("class upsert failed: " + fqn, e);
        }
    }

    @Override
    public Optional<Record> findByFqn(Fqn fqn) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, fqn, access, super_fqn, file_id, signature_raw, source, interfaces "
                        + "FROM classes WHERE fqn=?")) {
            ps.setString(1, fqn.slashed());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("class findByFqn failed", e);
        }
    }

    @Override
    public void setFileId(long classId, long fileId) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE classes SET file_id=? WHERE id=?")) {
            ps.setLong(1, fileId);
            ps.setLong(2, classId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("setFileId failed", e);
        }
    }

    @Override
    public int deleteByFqn(Fqn fqn) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM classes WHERE fqn=?")) {
            ps.setString(1, fqn.slashed());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("class deleteByFqn failed: " + fqn, e);
        }
    }

    @Override
    public List<Fqn> findSubtypesOf(Fqn iface) {
        // We check three places a class can be a subtype of `iface`:
        //   1. iface appears in the comma-separated `interfaces` column
        //   2. iface is the direct superclass (super_fqn)
        // LIKE on a comma-delimited column is fine at jrdi's scale; a future
        // optimization is to add a normalized join table.
        String slashed = iface.slashed();
        String like = "%," + slashed + ",%";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT fqn FROM classes WHERE super_fqn=? OR (',' || interfaces || ',') LIKE ?")) {
            ps.setString(1, slashed);
            ps.setString(2, like);
            try (ResultSet rs = ps.executeQuery()) {
                List<Fqn> out = new ArrayList<>();
                while (rs.next()) out.add(Fqn.of(rs.getString(1)));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("findSubtypesOf failed: " + iface, e);
        }
    }

    private Record map(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        Fqn fqn = Fqn.of(rs.getString("fqn"));
        int access = rs.getInt("access");
        String superRaw = rs.getString("super_fqn");
        Fqn superFqn = superRaw == null ? null : Fqn.of(superRaw);
        long fileIdRaw = rs.getLong("file_id");
        Long fileId = rs.wasNull() ? null : fileIdRaw;
        List<Fqn> interfaces = decodeInterfaces(rs.getString("interfaces"));
        return new Record(id, fqn, access, superFqn, fileId, rs.getString("signature_raw"),
                rs.getString("source"), interfaces);
    }

    /** Encode {@code [com/foo/I, com/bar/J]} → {@code ",com/foo/I,com/bar/J,"} (comma-bracketed). */
    private static String encodeInterfaces(List<Fqn> interfaces) {
        if (interfaces == null || interfaces.isEmpty()) return "";
        return "," + interfaces.stream().map(Fqn::slashed).collect(Collectors.joining(",")) + ",";
    }

    /** Inverse of {@link #encodeInterfaces}. */
    private static List<Fqn> decodeInterfaces(String s) {
        if (s == null || s.isEmpty()) return List.of();
        String trimmed = s.startsWith(",") ? s.substring(1) : s;
        if (trimmed.endsWith(",")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.isEmpty()) return List.of();
        return Arrays.stream(trimmed.split(","))
                .filter(x -> !x.isEmpty())
                .map(Fqn::of)
                .collect(Collectors.toList());
    }
}
