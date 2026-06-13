package io.jrdi.storage.repo;

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
