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
