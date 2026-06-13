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

import java.util.List;

public interface DubboServiceRepo extends Repo {

    record Record(long id, Fqn interfaceFqn, long implClassId,
                 String group, String version, String protocol, String source,
                 String refBeanName, String registryId) {}

    long upsert(Fqn interfaceFqn, long implClassId, String group, String version,
                String protocol, String source, String refBeanName, String registryId);

    List<Record> findByInterface(Fqn interfaceFqn);
}
