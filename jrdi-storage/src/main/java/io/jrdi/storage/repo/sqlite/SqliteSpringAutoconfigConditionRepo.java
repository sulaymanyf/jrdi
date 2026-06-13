package io.jrdi.storage.repo.sqlite;

import io.jrdi.storage.repo.SpringAutoconfigConditionRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class SqliteSpringAutoconfigConditionRepo implements SpringAutoconfigConditionRepo {

    private final DataSource ds;

    public SqliteSpringAutoconfigConditionRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(String autoconfigClass, String conditionType,
                       String requiredClass, String requiredBeanType,
                       String requiredProperty, String appliedTo) {
        String sql = """
                INSERT INTO spring_autoconfig_conditions(
                    autoconfig_class, condition_type,
                    required_class, required_bean_type, required_property, applied_to)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(autoconfig_class, condition_type, required_class,
                            required_bean_type, required_property, applied_to)
                DO NOTHING
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, autoconfigClass);
            ps.setString(2, conditionType);
            ps.setString(3, requiredClass == null ? "" : requiredClass);
            ps.setString(4, requiredBeanType == null ? "" : requiredBeanType);
            ps.setString(5, requiredProperty == null ? "" : requiredProperty);
            ps.setString(6, appliedTo == null || appliedTo.isEmpty() ? "class" : appliedTo);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            return -1L;
        } catch (SQLException e) {
            throw new RuntimeException("spring autoconfig condition upsert failed", e);
        }
    }

    @Override
    public List<Record> findByAutoconfigClass(String autoconfigClass) {
        return query("WHERE autoconfig_class=?", autoconfigClass);
    }

    @Override
    public List<Record> findByRequiredClass(String requiredClass) {
        return query("WHERE required_class=?", requiredClass);
    }

    @Override
    public List<Record> findByType(String conditionType) {
        return query("WHERE condition_type=?", conditionType);
    }

    @Override
    public List<Record> findAll() {
        return query("LIMIT 1000");
    }

    private List<Record> query(String where, Object... args) {
        String sql = "SELECT id, autoconfig_class, condition_type, " +
                "required_class, required_bean_type, required_property, applied_to " +
                "FROM spring_autoconfig_conditions " + where;
        List<Record> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Record(
                            rs.getLong("id"),
                            rs.getString("autoconfig_class"),
                            rs.getString("condition_type"),
                            rs.getString("required_class"),
                            rs.getString("required_bean_type"),
                            rs.getString("required_property"),
                            rs.getString("applied_to")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("spring autoconfig condition query failed", e);
        }
        return out;
    }
}
