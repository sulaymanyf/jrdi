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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jrdi.storage.repo.MybatisStatementRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public final class SqliteMybatisStatementRepo implements MybatisStatementRepo {

    private static final ObjectMapper M = new ObjectMapper();

    private final DataSource ds;

    public SqliteMybatisStatementRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(String namespace, String statementId, Kind kind,
                       String sqlTemplate, String sqlNormalized, List<String> parameters,
                       String definedInFile, Integer line,
                       String providerClass, String providerMethod) {
        String sql = """
                INSERT INTO mybatis_statements(
                    namespace, statement_id, kind, sql_template, sql_normalized,
                    parameters, defined_in_file, line, provider_class, provider_method)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(namespace, statement_id)
                DO UPDATE SET
                    kind           = excluded.kind,
                    sql_template   = excluded.sql_template,
                    sql_normalized = excluded.sql_normalized,
                    parameters     = excluded.parameters,
                    defined_in_file= excluded.defined_in_file,
                    line           = excluded.line,
                    provider_class = excluded.provider_class,
                    provider_method= excluded.provider_method
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, namespace);
            ps.setString(2, statementId);
            ps.setString(3, kind.name());
            ps.setString(4, sqlTemplate);
            ps.setString(5, sqlNormalized);
            ps.setString(6, M.writeValueAsString(parameters == null ? List.of() : parameters));
            ps.setString(7, definedInFile);
            if (line == null) ps.setNull(8, Types.INTEGER); else ps.setInt(8, line);
            ps.setString(9, providerClass == null ? "" : providerClass);
            ps.setString(10, providerMethod == null ? "" : providerMethod);
            int affected = ps.executeUpdate();
            if (affected == 1) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
            return findIdOnConnection(c, namespace, statementId);
        } catch (SQLException | JsonProcessingException e) {
            throw new RuntimeException("mybatis statement upsert failed", e);
        }
    }

    private long findIdOnConnection(Connection c, String namespace, String statementId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM mybatis_statements WHERE namespace=? AND statement_id=?")) {
            ps.setString(1, namespace);
            ps.setString(2, statementId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1L;
    }

    @Override
    public List<Record> findByNamespace(String namespace) {
        return query("WHERE namespace = ?", namespace);
    }

    @Override
    public List<Record> findByNamespaceAndId(String namespace, String statementId) {
        return query("WHERE namespace = ? AND statement_id = ?", namespace, statementId);
    }

    private List<Record> query(String where, Object... args) {
        String sql = "SELECT id, namespace, statement_id, kind, sql_template, sql_normalized, " +
                "parameters, defined_in_file, line, provider_class, provider_method " +
                "FROM mybatis_statements " + where;
        List<Record> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setString(i + 1, (String) args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int line = rs.getInt("line");
                    Integer lineBox = rs.wasNull() ? null : line;
                    String params = rs.getString("parameters");
                    List<String> paramList;
                    try {
                        paramList = M.readValue(params, M.getTypeFactory()
                                .constructCollectionType(List.class, String.class));
                    } catch (Exception e) {
                        paramList = List.of();
                    }
                    out.add(new Record(
                            rs.getLong("id"),
                            rs.getString("namespace"),
                            rs.getString("statement_id"),
                            Kind.valueOf(rs.getString("kind")),
                            rs.getString("sql_template"),
                            rs.getString("sql_normalized"),
                            paramList,
                            rs.getString("defined_in_file"),
                            lineBox,
                            rs.getString("provider_class"),
                            rs.getString("provider_method")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("mybatis statement query failed", e);
        }
        return out;
    }
}
