package io.jrdi.storage.repo;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;

import java.util.List;
import java.util.Optional;

public interface MethodRepo extends Repo {

    record Record(long id, long classId, String name, String desc,
                  String signatureRaw, Integer startLine, Integer endLine, boolean virtual) {
        public MethodKey key() {
            return new MethodKey(name, desc);
        }
    }

    long upsert(long classId, String name, String desc, String signatureRaw,
                Integer startLine, Integer endLine, boolean virtual);

    Optional<Record> findByKey(Fqn owner, MethodKey key);

    List<Record> findByClass(Fqn owner);
}
