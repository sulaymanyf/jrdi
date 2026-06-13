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

import io.jrdi.storage.repo.FileRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Optional;

public final class SqliteFileRepo implements FileRepo {

    private final DataSource ds;

    public SqliteFileRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(long artifactId, String relPath, String lang, long mtime, String sha256) {
        String sql = """
                INSERT INTO files(artifact_id, rel_path, lang, mtime, sha256)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(artifact_id, rel_path) DO UPDATE SET
                    lang=excluded.lang,
                    mtime=excluded.mtime,
                    sha256=excluded.sha256
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, artifactId);
            ps.setString(2, relPath);
            ps.setString(3, lang);
            ps.setLong(4, mtime);
            if (sha256 == null) ps.setNull(5, Types.VARCHAR);
            else ps.setString(5, sha256);
            int affected = ps.executeUpdate();
            if (affected == 1) {
                // Fresh insert: read the generated id.
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
            // Update path: ON CONFLICT updates don't yield a generated key, so we
            // need to look it up. We re-use the same connection to avoid pool
            // exhaustion under SQLite's pool-size-1 default.
            return findIdOnConnection(c, artifactId, relPath);
        } catch (SQLException e) {
            throw new RuntimeException("file upsert failed", e);
        }
    }

    private long findIdOnConnection(Connection c, long artifactId, String relPath) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM files WHERE artifact_id=? AND rel_path=?")) {
            ps.setLong(1, artifactId);
            ps.setString(2, relPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1L;
    }

    @Override
    public java.util.Optional<Record> findByPath(long artifactId, String relPath) {
        String sql = "SELECT id, artifact_id, rel_path, lang, mtime, sha256 FROM files WHERE artifact_id=? AND rel_path=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, artifactId);
            ps.setString(2, relPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                return java.util.Optional.of(new Record(
                        rs.getLong("id"),
                        rs.getLong("artifact_id"),
                        rs.getString("rel_path"),
                        rs.getString("lang"),
                        rs.getLong("mtime"),
                        rs.getString("sha256")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("file findByPath failed", e);
        }
    }

    @Override
    public java.util.Optional<Record> findById(long fileId) {
        String sql = "SELECT id, artifact_id, rel_path, lang, mtime, sha256 FROM files WHERE id=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                return java.util.Optional.of(new Record(
                        rs.getLong("id"),
                        rs.getLong("artifact_id"),
                        rs.getString("rel_path"),
                        rs.getString("lang"),
                        rs.getLong("mtime"),
                        rs.getString("sha256")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("file findById failed", e);
        }
    }

    @Override
    public void updateSha256(long fileId, String sha256) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE files SET sha256=? WHERE id=?")) {
            if (sha256 == null) ps.setNull(1, Types.VARCHAR);
            else ps.setString(1, sha256);
            ps.setLong(2, fileId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("updateSha256 failed", e);
        }
    }
}
