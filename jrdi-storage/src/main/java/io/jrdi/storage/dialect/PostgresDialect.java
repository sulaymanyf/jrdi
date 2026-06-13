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
 */package io.jrdi.storage.dialect;

public final class PostgresDialect implements Dialect {

    public static final PostgresDialect INSTANCE = new PostgresDialect();

    private PostgresDialect() {
    }

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public String pingSql() {
        return "SELECT 1";
    }

    @Override
    public String jsonExtract(String column, String path) {
        return "(" + column + " ->> '" + path + "')";
    }
}
