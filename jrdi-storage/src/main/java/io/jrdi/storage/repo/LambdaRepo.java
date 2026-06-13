package io.jrdi.storage.repo;

public interface LambdaRepo extends Repo {

    record Record(long id, long enclosingMethodId, Long syntheticMethodId, String bsmTarget, Integer line) {}

    long upsert(long enclosingMethodId, Long syntheticMethodId, String bsmTarget, Integer line);
}
