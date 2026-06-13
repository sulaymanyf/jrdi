package io.jrdi.storage.repo.sqlite;

import io.jrdi.storage.repo.RepoRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

public final class SqliteRepoRepo implements RepoRepo {

    private final DataSource ds;

    public SqliteRepoRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(String name, String rootPath, String vcsRev, String indexedAt) {
        // Look up the existing id first; if found, do a regular UPDATE. Otherwise
        // do a plain INSERT. Avoids the SQLite getGeneratedKeys()-after-ON-CONFLICT
        // pitfall (which returns the AUTOINCREMENT counter, not the rowid).
        Optional<Record> existing = findByName(name);
        if (existing.isPresent()) {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE repos SET root_path=?, vcs_rev=?, indexed_at=? WHERE id=?")) {
                ps.setString(1, rootPath);
                if (vcsRev == null) ps.setNull(2, java.sql.Types.VARCHAR);
                else ps.setString(2, vcsRev);
                ps.setString(3, indexedAt);
                ps.setLong(4, existing.get().id());
                ps.executeUpdate();
                return existing.get().id();
            } catch (SQLException e) {
                throw new RuntimeException("repo update failed: " + name, e);
            }
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO repos(name, root_path, vcs_rev, indexed_at) VALUES (?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, rootPath);
            if (vcsRev == null) ps.setNull(3, java.sql.Types.VARCHAR);
            else ps.setString(3, vcsRev);
            ps.setString(4, indexedAt);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            return -1L;
        } catch (SQLException e) {
            throw new RuntimeException("repo insert failed: " + name, e);
        }
    }

    @Override
    public Optional<Record> findByName(String name) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, root_path, vcs_rev, indexed_at FROM repos WHERE name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Record(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("root_path"),
                        rs.getString("vcs_rev"),
                        rs.getString("indexed_at")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("repo findByName failed", e);
        }
    }
}
