package io.jrdi.storage.repo;

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
