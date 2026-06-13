package io.jrdi.storage.repo;

public interface FileRepo extends Repo {

    record Record(long id, long artifactId, String relPath, String lang, long mtime, String sha256) {}

    long upsert(long artifactId, String relPath, String lang, long mtime, String sha256);

    java.util.Optional<Record> findByPath(long artifactId, String relPath);

    java.util.Optional<Record> findById(long fileId);

    void updateSha256(long fileId, String sha256);
}
