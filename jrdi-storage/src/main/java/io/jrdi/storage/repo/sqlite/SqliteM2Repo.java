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
 */
package io.jrdi.storage.repo.sqlite;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.repo.M2Repo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteM2Repo implements M2Repo {

    private final DataSource ds;

    public SqliteM2Repo(DataSource ds) {
        this.ds = ds;
    }

    // ─── Cache ───────────────────────────────────────────────────────

    @Override
    public Optional<CacheRow> findCache(String jarPath) {
        String sql = "SELECT id, jar_path, jar_sha256, jar_mtime_ms, classes_count, " +
                     "methods_count, invokes_count, extracted_at, last_access_at " +
                     "FROM m2_caches WHERE jar_path = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, jarPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(toCacheRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("m2_caches find failed", e);
        }
        return Optional.empty();
    }

    @Override
    public List<CacheRow> findCacheBySha(String jarSha256) {
        String sql = "SELECT id, jar_path, jar_sha256, jar_mtime_ms, classes_count, " +
                     "methods_count, invokes_count, extracted_at, last_access_at " +
                     "FROM m2_caches WHERE jar_sha256 = ?";
        List<CacheRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, jarSha256);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(toCacheRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("m2_caches findBySha failed", e);
        }
        return out;
    }

    @Override
    public long upsertCache(String jarPath, String jarSha256, long jarMtimeMs,
                            int classesCount, int methodsCount, int invokesCount) {
        String sql = """
                INSERT INTO m2_caches(
                    jar_path, jar_sha256, jar_mtime_ms,
                    classes_count, methods_count, invokes_count,
                    extracted_at, last_access_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(jar_path) DO UPDATE SET
                    jar_sha256 = excluded.jar_sha256,
                    jar_mtime_ms = excluded.jar_mtime_ms,
                    classes_count = excluded.classes_count,
                    methods_count = excluded.methods_count,
                    invokes_count = excluded.invokes_count,
                    extracted_at = excluded.extracted_at
                """;
        String now = Instant.now().toString();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, jarPath);
            ps.setString(2, jarSha256);
            ps.setLong(3, jarMtimeMs);
            ps.setInt(4, classesCount);
            ps.setInt(5, methodsCount);
            ps.setInt(6, invokesCount);
            ps.setString(7, now);
            ps.setString(8, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("m2_caches upsert failed", e);
        }
        // SQLite's last_insert_rowid() is process-local; since we
        // may have just collided, fetch the canonical id.
        return findCache(jarPath).orElseThrow().id();
    }

    @Override
    public void touchCache(long cacheId) {
        String sql = "UPDATE m2_caches SET last_access_at = ? WHERE id = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setLong(2, cacheId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("m2_caches touch failed", e);
        }
    }

    @Override
    public void evictCache(long cacheId) {
        // ON DELETE CASCADE on m2_classes / m2_methods / m2_invokes
        // handles the rest. The cache row is the root.
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM m2_caches WHERE id = ?")) {
            ps.setLong(1, cacheId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("m2_caches evict failed", e);
        }
    }

    @Override
    public List<CacheRow> oldestCaches(int limit) {
        String sql = "SELECT id, jar_path, jar_sha256, jar_mtime_ms, classes_count, " +
                     "methods_count, invokes_count, extracted_at, last_access_at " +
                     "FROM m2_caches ORDER BY last_access_at ASC LIMIT ?";
        List<CacheRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(toCacheRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("m2_caches oldest failed", e);
        }
        return out;
    }

    private static CacheRow toCacheRow(ResultSet rs) throws SQLException {
        return new CacheRow(
                rs.getLong("id"),
                rs.getString("jar_path"),
                rs.getString("jar_sha256"),
                rs.getLong("jar_mtime_ms"),
                rs.getInt("classes_count"),
                rs.getInt("methods_count"),
                rs.getInt("invokes_count"),
                rs.getString("extracted_at"),
                rs.getString("last_access_at"));
    }

    // ─── Classes / methods / invokes ─────────────────────────────────

    @Override
    public long insertM2Class(Fqn fqn, Fqn superFqn, int access, String interfacesCsv,
                              String jarPath) {
        String sql = """
                INSERT INTO m2_classes(fqn, super_fqn, access, interfaces, jar_path)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(fqn, jar_path) DO UPDATE SET
                    super_fqn = excluded.super_fqn,
                    access = excluded.access,
                    interfaces = excluded.interfaces
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fqn.slashed());
            ps.setString(2, superFqn == null ? "" : superFqn.slashed());
            ps.setInt(3, access);
            ps.setString(4, interfacesCsv == null ? "" : interfacesCsv);
            ps.setString(5, jarPath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("m2_classes upsert failed", e);
        }
        return findM2Class(fqn, jarPath).orElseThrow().id();
    }

    @Override
    public Optional<ClassRow> findM2Class(Fqn fqn, String jarPath) {
        String sql = "SELECT id, fqn, super_fqn, access, interfaces, jar_path " +
                     "FROM m2_classes WHERE fqn = ? AND jar_path = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fqn.slashed());
            ps.setString(2, jarPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(toClassRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("m2_classes find failed", e);
        }
        return Optional.empty();
    }

    @Override
    public List<ClassRow> findM2ClassesByFqn(Fqn fqn) {
        String sql = "SELECT id, fqn, super_fqn, access, interfaces, jar_path " +
                     "FROM m2_classes WHERE fqn = ?";
        List<ClassRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fqn.slashed());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(toClassRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("m2_classes findByFqn failed", e);
        }
        return out;
    }

    private static ClassRow toClassRow(ResultSet rs) throws SQLException {
        return new ClassRow(
                rs.getLong("id"),
                Fqn.of(rs.getString("fqn")),
                Fqn.of(rs.getString("super_fqn")),
                rs.getInt("access"),
                rs.getString("interfaces"),
                rs.getString("jar_path"));
    }

    @Override
    public long insertM2Method(long classId, String name, String desc, int access,
                               Integer line) {
        String sql = """
                INSERT INTO m2_methods(class_id, name, "desc", access, line)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(class_id, name, "desc") DO UPDATE SET
                    access = excluded.access,
                    line = excluded.line
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, classId);
            ps.setString(2, name);
            ps.setString(3, desc);
            ps.setInt(4, access);
            if (line == null) ps.setNull(5, java.sql.Types.INTEGER);
            else ps.setInt(5, line);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("m2_methods upsert failed", e);
        }
        // Find the row id.
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM m2_methods WHERE class_id = ? AND name = ? AND \"desc\" = ?")) {
            ps.setLong(1, classId);
            ps.setString(2, name);
            ps.setString(3, desc);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("m2_methods id lookup failed", e);
        }
        return -1L;
    }

    @Override
    public void insertM2Invoke(String callerClassFqn, long callerMethodId,
                               String calleeClassFqn, String calleeMethodName,
                               String calleeMethodDesc, String callKind) {
        // No UNIQUE key on m2_invokes: multiple INVOKEVIRTUAL of
        // the same target from the same call site are distinct edges
        // in the call graph (we don't have a position to dedupe on).
        // The insert is plain; idempotency is handled at a higher
        // level (re-extracting a jar deletes the cache row, which
        // cascades the invokes).
        String sql = "INSERT INTO m2_invokes(" +
                     "caller_class_fqn, caller_method_id, callee_class_fqn, " +
                     "callee_method_name, callee_method_desc, call_kind) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, callerClassFqn);
            ps.setLong(2, callerMethodId);
            ps.setString(3, calleeClassFqn);
            ps.setString(4, calleeMethodName);
            ps.setString(5, calleeMethodDesc);
            ps.setString(6, callKind == null ? "invoke" : callKind);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("m2_invokes insert failed", e);
        }
    }

    @Override
    public List<MethodRow> methodsOf(long m2ClassId) {
        String sql = "SELECT id, class_id, name, \"desc\", access, line " +
                     "FROM m2_methods WHERE class_id = ?";
        List<MethodRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, m2ClassId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(toMethodRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("m2_methods query failed", e);
        }
        return out;
    }

    @Override
    public List<InvokeRow> outgoingInvokes(String callerClassFqn) {
        String sql = "SELECT i.id, i.caller_class_fqn, i.caller_method_id, " +
                     "i.callee_class_fqn, i.callee_method_name, i.callee_method_desc, " +
                     "i.call_kind " +
                     "FROM m2_invokes i " +
                     "JOIN m2_methods m ON m.id = i.caller_method_id " +
                     "WHERE i.caller_class_fqn = ?";
        List<InvokeRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, callerClassFqn);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(toInvokeRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("m2_invokes outgoing failed", e);
        }
        return out;
    }

    @Override
    public List<InvokeRow> incomingInvokes(String calleeClassFqn, String calleeMethodName) {
        String sql = "SELECT id, caller_class_fqn, caller_method_id, " +
                     "callee_class_fqn, callee_method_name, callee_method_desc, " +
                     "call_kind FROM m2_invokes " +
                     "WHERE callee_class_fqn = ? AND callee_method_name = ?";
        List<InvokeRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, calleeClassFqn);
            ps.setString(2, calleeMethodName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(toInvokeRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("m2_invokes incoming failed", e);
        }
        return out;
    }

    private static MethodRow toMethodRow(ResultSet rs) throws SQLException {
        int line = rs.getInt("line");
        Integer lineBox = rs.wasNull() ? null : line;
        return new MethodRow(
                rs.getLong("id"),
                rs.getLong("class_id"),
                rs.getString("name"),
                rs.getString("desc"),
                rs.getInt("access"),
                lineBox);
    }

    private static InvokeRow toInvokeRow(ResultSet rs) throws SQLException {
        return new InvokeRow(
                rs.getLong("id"),
                rs.getString("caller_class_fqn"),
                rs.getLong("caller_method_id"),
                rs.getString("callee_class_fqn"),
                rs.getString("callee_method_name"),
                rs.getString("callee_method_desc"),
                rs.getString("call_kind"));
    }

    // ─── Repo housekeeping ──────────────────────────────────────────

    // The DataSource is owned by Db; nothing to close here.
}
