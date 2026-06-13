package io.jrdi.storage.repo;

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
