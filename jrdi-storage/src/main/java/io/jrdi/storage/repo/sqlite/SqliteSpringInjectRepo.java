package io.jrdi.storage.repo.sqlite;

import io.jrdi.storage.repo.SpringInjectRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public final class SqliteSpringInjectRepo implements SpringInjectRepo {

    private static final ObjectMapper M = new ObjectMapper();

    private final DataSource ds;

    public SqliteSpringInjectRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void record(String targetField, Integer targetParamIndex, long classId, Long methodId,
                       String qualifier, By by, Confidence confidence, List<Long> candidateBeanIds) {
        String sql = """
                INSERT INTO spring_injects(target_field, target_param_index, class_id, method_id,
                    qualifier, by_value, confidence, candidate_bean_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (targetField == null) ps.setNull(1, Types.VARCHAR); else ps.setString(1, targetField);
            if (targetParamIndex == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, targetParamIndex);
            ps.setLong(3, classId);
            if (methodId == null) ps.setNull(4, Types.INTEGER); else ps.setLong(4, methodId);
            if (qualifier == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, qualifier);
            ps.setString(6, by.name());
            ps.setString(7, confidence.name());
            ps.setString(8, M.writeValueAsString(candidateBeanIds == null ? List.of() : candidateBeanIds));
            ps.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            throw new RuntimeException("spring inject insert failed", e);
        }
    }

    @Override
    public List<Record> findByClass(long classId) {
        String sql = "SELECT id, target_field, target_param_index, class_id, method_id, " +
                "qualifier, by_value, confidence, candidate_bean_ids FROM spring_injects WHERE class_id = ?";
        List<Record> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int paramIdx = rs.getInt("target_param_index");
                    Integer param = rs.wasNull() ? null : paramIdx;
                    long methodIdRaw = rs.getLong("method_id");
                    Long methodId = rs.wasNull() ? null : methodIdRaw;
                    String candidates = rs.getString("candidate_bean_ids");
                    List<Long> ids;
                    try {
                        ids = M.readValue(candidates, M.getTypeFactory()
                                .constructCollectionType(List.class, Long.class));
                    } catch (Exception e) {
                        ids = List.of();
                    }
                    out.add(new Record(
                            rs.getLong("id"),
                            rs.getString("target_field"),
                            param,
                            rs.getLong("class_id"),
                            methodId,
                            rs.getString("qualifier"),
                            By.valueOf(rs.getString("by_value")),
                            Confidence.valueOf(rs.getString("confidence")),
                            ids));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("spring inject query failed", e);
        }
        return out;
    }
}
