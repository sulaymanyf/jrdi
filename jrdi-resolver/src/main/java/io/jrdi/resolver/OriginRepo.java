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

/**
 * Identifies a logical Maven repository the artifact came from. The default {@link #LOCAL}
 * covers {@code ~/.m2/repository}; custom remotes (configured in {@code settings.xml}) get
 * the URL of the {@code <repository>} or {@code <mirror>}.
 */
public record OriginRepo(String id, String url) {

    public static final OriginRepo LOCAL = new OriginRepo("local", "file://~/.m2/repository");

    public boolean isLocal() {
        return "local".equals(id);
    }
}
