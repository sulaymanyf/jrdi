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
import java.util.Optional;

/**
 * Dubbo registry declarations. Each row is a {@code <dubbo:registry id="..."/>}
 * element parsed from a Spring XML config.
 *
 * <p>The LLM uses this to answer:
 *   "which registries does this project connect to?"
 *   "which services are bound to ZooKeeper vs Nacos?"
 */
public interface DubboRegistryRepo extends Repo {

    record Record(long id, String registryId, String address, String protocol,
                  Integer port, String username, String parameters,
                  String sourceFile) {}

    long upsert(String registryId, String address, String protocol, Integer port,
                String username, String parameters, String sourceFile);

    Optional<Record> findByRegistryId(String registryId);

    List<Record> findByProtocol(String protocol);

    List<Record> findAll();
}
