package io.jrdi.storage.repo;

import java.util.List;

/**
 * Per-class condition rows for Spring Boot auto-configurations. Each row is a
 * single {@code @Conditional*} annotation extracted from an auto-config class
 * (or one of its {@code @Bean} methods).
 *
 * <p>The LLM uses this to answer questions like "which Spring Boot auto-configs
 * will actually fire given my classpath?" by joining with
 * {@code spring_boot_autoconfigs} (V5) and {@code classes} (V1) — if the
 * required class is in any indexed jar, the condition is met.
 */
public interface SpringAutoconfigConditionRepo extends Repo {

    record Record(long id, String autoconfigClass, String conditionType,
                  String requiredClass, String requiredBeanType,
                  String requiredProperty, String appliedTo) {}

    /**
     * Idempotent upsert keyed on
     * {@code (autoconfig_class, condition_type, required_class, required_bean_type, required_property, applied_to)}.
     */
    long upsert(String autoconfigClass, String conditionType,
                String requiredClass, String requiredBeanType,
                String requiredProperty, String appliedTo);

    /** Find every condition on the given auto-config class. */
    List<Record> findByAutoconfigClass(String autoconfigClass);

    /** Find every condition that requires the given class FQN. */
    List<Record> findByRequiredClass(String requiredClass);

    /** Find every condition of a given type. */
    List<Record> findByType(String conditionType);

    /** Convenience: every recorded condition (capped at 1000). */
    List<Record> findAll();
}
