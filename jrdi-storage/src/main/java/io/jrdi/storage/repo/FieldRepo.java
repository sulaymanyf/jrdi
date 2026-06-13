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

public interface FieldRepo extends Repo {

    record Record(long id, long classId, String name, String desc, String signatureRaw, Integer line) {}

    long upsert(long classId, String name, String desc, String signatureRaw, Integer line);

    /**
     * Find a field by (owner, name, descriptor). Used by Dubbo/MyBatis analyzers.
     */
    java.util.Optional<Record> findByKey(io.jrdi.core.symbol.Fqn owner,
                                          io.jrdi.core.symbol.MethodKey key);
}
