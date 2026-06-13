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
