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

import io.jrdi.storage.repo.LambdaRepo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

public final class SqliteLambdaRepo implements LambdaRepo {

    private final DataSource ds;

    public SqliteLambdaRepo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public long upsert(long enclosingMethodId, Long syntheticMethodId, String bsmTarget, Integer line) {
        String sql = """
                INSERT INTO lambdas(enclosing_method_id, synthetic_method_id, bsm_target, line)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, enclosingMethodId);
            if (syntheticMethodId == null) ps.setNull(2, Types.INTEGER);
            else ps.setLong(2, syntheticMethodId);
            if (bsmTarget == null) ps.setNull(3, Types.VARCHAR);
            else ps.setString(3, bsmTarget);
            if (line == null) ps.setNull(4, Types.INTEGER);
            else ps.setInt(4, line);
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            return -1L;
        } catch (SQLException e) {
            throw new RuntimeException("lambda upsert failed", e);
        }
    }
}
