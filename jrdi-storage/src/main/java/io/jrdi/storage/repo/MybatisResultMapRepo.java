package io.jrdi.storage.repo;

import java.util.List;

/**
 * Per-result-map summary. Each row corresponds to a {@code <resultMap>} element in
 * a MyBatis {@code *Mapper.xml} file. We record the type, the inheritance chain,
 * and counts of nested properties / associations / collections so the LLM can
 * quickly tell "is this a flat row mapper or a deep graph".
 */
public interface MybatisResultMapRepo extends Repo {

    record Record(long id, String namespace, String mapId, String typeFqn,
                  String extendsRef, boolean autoMapping,
                  int propertyCount, int associationCount, int collectionCount) {}

    /** Idempotent upsert keyed on {@code (namespace, map_id)}. */
    long upsert(String namespace, String mapId, String typeFqn, String extendsRef,
                boolean autoMapping, int propertyCount,
                int associationCount, int collectionCount);

    List<Record> findByNamespace(String namespace);

    List<Record> findByType(String typeFqn);
}
