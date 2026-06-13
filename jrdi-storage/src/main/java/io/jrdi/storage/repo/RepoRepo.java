package io.jrdi.storage.repo;

public interface RepoRepo extends Repo {

    record Record(long id, String name, String rootPath, String vcsRev, String indexedAt) {}

    long upsert(String name, String rootPath, String vcsRev, String indexedAt);

    java.util.Optional<Record> findByName(String name);
}
