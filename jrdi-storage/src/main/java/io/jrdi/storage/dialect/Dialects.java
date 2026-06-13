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

import java.util.Locale;

public final class Dialects {

    private Dialects() {
    }

    public static Dialect detectFromJdbcUrl(String url) {
        if (url == null) {
            return SqliteDialect.INSTANCE;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:postgresql:") || lower.startsWith("jdbc:pg:")) {
            return PostgresDialect.INSTANCE;
        }
        if (lower.startsWith("jdbc:sqlite:") || lower.startsWith("jdbc:sqlite-")) {
            return SqliteDialect.INSTANCE;
        }
        throw new IllegalArgumentException("unsupported JDBC URL: " + url);
    }
}
