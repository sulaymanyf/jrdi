package io.jrdi.storage.repo;

import java.util.List;
import java.util.Optional;

/**
 * Per-method Dubbo tuning config. Each row corresponds to a {@code <dubbo:method>}
 * element nested inside a {@code <dubbo:service>} or {@code <dubbo:reference>} XML
 * element. Either {@code service_id} or {@code reference_id} is non-zero (the
 * other is 0).
 */
public interface DubboMethodConfigRepo extends Repo {

    record Record(long id, long serviceId, long referenceId, String methodName,
                  Integer timeoutMs, Integer retries, String loadbalance,
                  boolean async, boolean sent) {}

    /**
     * Idempotent upsert keyed on {@code (service_id, reference_id, method_name)}.
     * Both service_id and reference_id may be 0 to mean "not set" — exactly one
     * of them is non-zero in practice.
     */
    long upsert(long serviceId, long referenceId, String methodName,
                Integer timeoutMs, Integer retries, String loadbalance,
                boolean async, boolean sent);

    List<Record> findByService(long serviceId);

    List<Record> findByReference(long referenceId);

    Optional<Record> findByServiceAndMethod(long serviceId, String methodName);
}
