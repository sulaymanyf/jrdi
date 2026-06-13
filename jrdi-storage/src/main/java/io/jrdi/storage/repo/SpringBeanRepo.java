package io.jrdi.storage.repo;

import io.jrdi.core.symbol.Fqn;

import java.util.List;
import java.util.Optional;

public interface SpringBeanRepo extends Repo {

    record Record(long id, String name, String typeFqn, String source,
                 Long classId, Long methodId, String scope, boolean primary) {}

    long upsert(String name, Fqn typeFqn, String source, Long classId, Long methodId,
                String scope, boolean primary);

    List<Record> findByType(Fqn typeFqn);

    List<Record> findByName(String name);

    Optional<Record> findByNameAndType(String name, Fqn typeFqn);

    /**
     * Return every recorded bean. Used by the SpringPass candidate resolution to
     * find cross-class candidates without per-type queries. Capped at 10,000 rows
     * for safety (the table is meant to be small).
     */
    List<Record> findAll();
}
