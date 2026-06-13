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
 */package io.jrdi.storage.repo;

import io.jrdi.core.coord.Gav;

import javax.sql.DataSource;
import java.util.Optional;

public interface ArtifactRepo extends Repo {

    record Record(long id, long repoId, Gav gav, String sha256, boolean hasSources, String jarPath, int score) {}

    long upsert(long repoId, Gav gav, String sha256, boolean hasSources, String jarPath, int score);

    Optional<Record> findByGav(long repoId, Gav gav);

    Optional<Record> findByGavGroupArtifact(String group, String artifact);

    Optional<Record> findById(long artifactId);

    void deleteByRepo(long repoId);

    DataSource dataSource();
}
