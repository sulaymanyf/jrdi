package io.jrdi.storage.repo.sqlite;

import io.jrdi.storage.repo.SpringBootAutoconfigRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class SqliteSpringBootAutoconfigRepo implements SpringBootAutoconfigRepo {

    private final DataSource ds;

    public SqliteSpringBootAutoconfigRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(String classFqn, String sourceFile, String sourceFormat, String keyInFactories) {
        String sql = """
                INSERT INTO spring_boot_autoconfigs(
                    class_fqn, source_file, source_format, key_in_factories)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(class_fqn, source_file, source_format, key_in_factories)
                DO NOTHING
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, classFqn);
            ps.setString(2, sourceFile);
            ps.setString(3, sourceFormat);
            ps.setString(4, keyInFactories == null ? "" : keyInFactories);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            return findIdOnConnection(c, classFqn, sourceFile, sourceFormat, keyInFactories);
        } catch (SQLException e) {
            throw new RuntimeException("spring boot autoconfig upsert failed", e);
        }
    }

    private long findIdOnConnection(Connection c, String classFqn, String sourceFile,
                                    String sourceFormat, String keyInFactories) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM spring_boot_autoconfigs " +
                "WHERE class_fqn=? AND source_file=? AND source_format=? AND key_in_factories=?")) {
            ps.setString(1, classFqn);
            ps.setString(2, sourceFile);
            ps.setString(3, sourceFormat);
            ps.setString(4, keyInFactories == null ? "" : keyInFactories);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1L;
    }

    @Override
    public List<Record> findByClass(String classFqn) {
        return query("WHERE class_fqn=?", classFqn);
    }

    @Override
    public List<Record> findByKey(String key) {
        return query("WHERE key_in_factories=?", key);
    }

    @Override
    public List<Record> findByFormat(String format) {
        return query("WHERE source_format=?", format);
    }

    @Override
    public List<Record> findAll() {
        return query("LIMIT 1000");
    }

    private List<Record> query(String where, Object... args) {
        String sql = "SELECT id, class_fqn, source_file, source_format, key_in_factories " +
                "FROM spring_boot_autoconfigs " + where;
        List<Record> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Record(
                            rs.getLong("id"),
                            rs.getString("class_fqn"),
                            rs.getString("source_file"),
                            rs.getString("source_format"),
                            rs.getString("key_in_factories")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("spring boot autoconfig query failed", e);
        }
        return out;
    }
}
