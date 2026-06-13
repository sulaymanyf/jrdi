package io.jrdi.storage.repo;

public interface FieldRepo extends Repo {

    record Record(long id, long classId, String name, String desc, String signatureRaw, Integer line) {}

    long upsert(long classId, String name, String desc, String signatureRaw, Integer line);

    /**
     * Find a field by (owner, name, descriptor). Used by Dubbo/MyBatis analyzers.
     */
    java.util.Optional<Record> findByKey(io.jrdi.core.symbol.Fqn owner,
                                          io.jrdi.core.symbol.MethodKey key);
}
