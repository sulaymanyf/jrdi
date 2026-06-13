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

import io.jrdi.core.coord.Gav;
import io.jrdi.storage.repo.ArtifactRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

public final class SqliteArtifactRepo implements ArtifactRepo {

    private final DataSource ds;

    public SqliteArtifactRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public DataSource dataSource() {
        return ds;
    }

    @Override
    public long upsert(long repoId, Gav gav, String sha256, boolean hasSources, String jarPath, int score) {
        // Look up the existing id first to side-step the SQLite
        // getGeneratedKeys-after-ON-CONFLICT pitfall (returns AUTOINCREMENT counter, not rowid).
        Optional<Record> existing = findByGav(repoId, gav);
        if (existing.isPresent()) {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE artifacts SET sha256=?, has_sources=?, jar_path=?, score=? WHERE id=?")) {
                if (sha256 == null) ps.setNull(1, java.sql.Types.VARCHAR);
                else ps.setString(1, sha256);
                ps.setInt(2, hasSources ? 1 : 0);
                ps.setString(3, jarPath);
                ps.setInt(4, score);
                ps.setLong(5, existing.get().id());
                ps.executeUpdate();
                return existing.get().id();
            } catch (SQLException e) {
                throw new RuntimeException("artifact update failed: " + gav, e);
            }
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO artifacts(repo_id, gav_group, gav_artifact, gav_version, sha256, has_sources, jar_path, score) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, repoId);
            ps.setString(2, gav.group());
            ps.setString(3, gav.artifact());
            ps.setString(4, gav.version());
            if (sha256 == null) ps.setNull(5, java.sql.Types.VARCHAR);
            else ps.setString(5, sha256);
            ps.setInt(6, hasSources ? 1 : 0);
            ps.setString(7, jarPath);
            ps.setInt(8, score);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            return -1L;
        } catch (SQLException e) {
            throw new RuntimeException("artifact insert failed: " + gav, e);
        }
    }

    @Override
    public Optional<Record> findByGav(long repoId, Gav gav) {
        String sql = "SELECT id, repo_id, gav_group, gav_artifact, gav_version, sha256, has_sources, jar_path, score "
                + "FROM artifacts WHERE repo_id=? AND gav_group=? AND gav_artifact=? AND gav_version=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, repoId);
            ps.setString(2, gav.group());
            ps.setString(3, gav.artifact());
            ps.setString(4, gav.version());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findByGav failed", e);
        }
    }

    @Override
    public Optional<Record> findByGavGroupArtifact(String group, String artifact) {
        String sql = "SELECT id, repo_id, gav_group, gav_artifact, gav_version, sha256, has_sources, jar_path, score "
                + "FROM artifacts WHERE gav_group=? AND gav_artifact=? "
                + "ORDER BY gav_version DESC LIMIT 1";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, group);
            ps.setString(2, artifact);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findByGavGroupArtifact failed", e);
        }
    }

    @Override
    public Optional<Record> findById(long artifactId) {
        String sql = "SELECT id, repo_id, gav_group, gav_artifact, gav_version, sha256, has_sources, jar_path, score "
                + "FROM artifacts WHERE id=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, artifactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findById failed", e);
        }
    }

    @Override
    public void deleteByRepo(long repoId) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM artifacts WHERE repo_id=?")) {
            ps.setLong(1, repoId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("deleteByRepo failed", e);
        }
    }

    private Record map(ResultSet rs) throws SQLException {
        return new Record(
                rs.getLong("id"),
                rs.getLong("repo_id"),
                Gav.of(rs.getString("gav_group"), rs.getString("gav_artifact"), rs.getString("gav_version")),
                rs.getString("sha256"),
                rs.getInt("has_sources") != 0,
                rs.getString("jar_path"),
                rs.getInt("score")
        );
    }
}
