package io.jrdi.storage.repo;

import io.jrdi.core.symbol.Fqn;

import java.util.List;

public interface DubboReferenceRepo extends Repo {

    enum Confidence { CERTAIN, PROBABLE, UNCERTAIN }

    record Record(long id, Fqn interfaceFqn, long fieldId,
                 String group, String version, Confidence confidence, String refId,
                 String registryId, String consumerClassFqn) {}

    /**
     * Idempotent upsert. If a row already exists for
     * {@code (interface_fqn, group_name, version, ref_id, field_id)} it is updated
     * in place; otherwise a new row is inserted.
     *
     * <p>For annotation-discovered references, {@code refId} is empty (the
     * Java field is the only thing pointing at the reference). For
     * XML-discovered references ({@code <dubbo:reference id="someId" interface="X"/>}),
     * {@code refId} is the XML {@code id} attribute and {@code fieldId} is
     * {@code 0} (no Java field involved).
     *
     * <p>{@code consumerClassFqn} is the FQN of the class that holds the
     * reference (the class with the {@code @DubboReference} field, or the
     * Spring bean that contains the {@code <dubbo:reference>}). Nullable/empty
     * for backward compatibility with older callers. When the consumer lives
     * in a module outside the indexed project, the field_id is 0 and the FQN
     * is the only handle on the consumer.
     */
    void record(Fqn interfaceFqn, long fieldId, String group, String version,
                Confidence confidence, String refId, String registryId, String consumerClassFqn);

    List<Record> findByField(long fieldId);

    List<Record> findByInterface(Fqn interfaceFqn);

    /**
     * Find references whose consumer is in the given class. Used by the
     * {@code consumerClass} argument of {@code find_dubbo_references}.
     */
    List<Record> findByConsumerClass(Fqn consumerClass);
}
