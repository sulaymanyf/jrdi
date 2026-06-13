package io.jrdi.storage.repo.sqlite;

import io.jrdi.storage.repo.IssueRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class SqliteIssueRepo implements IssueRepo {

    private final DataSource ds;

    public SqliteIssueRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void record(String kind, String target, String message, Severity severity, String detectedAt) {
        String sql = "INSERT INTO issues(kind, target, message, severity, detected_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, kind);
            ps.setString(2, target);
            ps.setString(3, message);
            ps.setString(4, severity.name());
            ps.setString(5, detectedAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("issue record failed", e);
        }
    }

    @Override
    public List<Record> findByKind(String kind) {
        String sql = "SELECT id, kind, target, message, severity, detected_at FROM issues WHERE kind=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                List<Record> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Record(
                            rs.getLong("id"),
                            rs.getString("kind"),
                            rs.getString("target"),
                            rs.getString("message"),
                            Severity.valueOf(rs.getString("severity")),
                            rs.getString("detected_at")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("findByKind failed", e);
        }
    }
}
