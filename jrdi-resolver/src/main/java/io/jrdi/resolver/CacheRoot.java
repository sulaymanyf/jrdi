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
 */package io.jrdi.resolver;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Picks a sane default cache location ({@code $HOME/.jrdi/cache}) with environment
 * variable override {@code JRDI_CACHE}.
 */
public final class CacheRoot {

    public static final String ENV_KEY = "JRDI_CACHE";

    private CacheRoot() {
    }

    public static Path resolve() {
        String env = System.getenv(ENV_KEY);
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        return Paths.get(System.getProperty("user.home"), ".jrdi", "cache");
    }
}
