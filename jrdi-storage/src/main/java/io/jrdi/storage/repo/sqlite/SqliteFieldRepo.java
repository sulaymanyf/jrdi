package io.jrdi.storage.repo.sqlite;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;
import io.jrdi.storage.repo.FieldRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;

public final class SqliteFieldRepo implements FieldRepo {

    private final DataSource ds;

    public SqliteFieldRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(long classId, String name, String desc, String signatureRaw, Integer line) {
        String sql = """
                INSERT INTO fields(class_id, name, "desc", signature_raw, line)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(class_id, name, "desc") DO UPDATE SET
                    signature_raw=COALESCE(excluded.signature_raw, fields.signature_raw),
                    line=COALESCE(excluded.line, fields.line)
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, classId);
            ps.setString(2, name);
            ps.setString(3, desc);
            if (signatureRaw == null) ps.setNull(4, Types.VARCHAR);
            else ps.setString(4, signatureRaw);
            if (line == null) ps.setNull(5, Types.INTEGER);
            else ps.setInt(5, line);
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            return -1L;
        } catch (SQLException e) {
            throw new RuntimeException("field upsert failed", e);
        }
    }

    @Override
    public Optional<Record> findByKey(Fqn owner, MethodKey key) {
        String sql = "SELECT f.id, f.class_id, f.name, f.\"desc\", f.signature_raw, f.line " +
                "FROM fields f JOIN classes c ON c.id = f.class_id " +
                "WHERE c.fqn = ? AND f.name = ? AND f.\"desc\" = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner.slashed());
            ps.setString(2, key.name());
            ps.setString(3, key.descriptor());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int line = rs.getInt("line");
                    return Optional.of(new Record(
                            rs.getLong("id"),
                            rs.getLong("class_id"),
                            rs.getString("name"),
                            rs.getString("desc"),
                            rs.getString("signature_raw"),
                            rs.wasNull() ? null : line));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("field findByKey failed", e);
        }
    }
}
