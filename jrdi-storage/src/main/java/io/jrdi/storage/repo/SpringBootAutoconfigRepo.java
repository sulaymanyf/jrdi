package io.jrdi.storage.repo;

import java.util.List;
import java.util.Set;

/**
 * Spring Boot auto-configuration registry. Each row is a discovered entry in
 * either {@code META-INF/spring.factories} (legacy, pre-3.0) or
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * (modern, 3.0+).
 *
 * <p>The LLM uses this to answer questions like "what auto-configurations does
 * this project's classpath pull in?" and "is this dependency pre-3.0
 * (spring.factories) or 3.0+ (imports) style?".
 */
public interface SpringBootAutoconfigRepo extends Repo {

    record Record(long id, String classFqn, String sourceFile, String sourceFormat,
                  String keyInFactories) {}

    /**
     * Idempotent upsert keyed on
     * {@code (class_fqn, source_file, source_format, key_in_factories)}.
     * For .imports files, {@code keyInFactories} is always "".
     */
    long upsert(String classFqn, String sourceFile, String sourceFormat, String keyInFactories);

    /** Find every auto-config that targets the given FQN (exact match). */
    List<Record> findByClass(String classFqn);

    /**
     * Find every auto-config under a {@code spring.factories} key like
     * {@code org.springframework.boot.autoconfigure.EnableAutoConfiguration}.
     * For .imports files the key is always "" — use {@link #findByFormat(String)}
     * to get those.
     */
    List<Record> findByKey(String key);

    /** Find every auto-config discovered via a given format ("factories" or "imports"). */
    List<Record> findByFormat(String format);

    /** Convenience: every recorded auto-config (capped at 1000). */
    List<Record> findAll();

    /** The set of well-known {@code spring.factories} keys we care about. */
    Set<String> KNOWN_KEYS = Set.of(
            "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
            "org.springframework.context.ApplicationListener",
            "org.springframework.context.ApplicationContextInitializer",
            "org.springframework.web.servlet.HandlerAdapter",
            "org.springframework.web.servlet.HandlerResolver",
            "org.springframework.boot.env.EnvironmentPostProcessor",
            "org.springframework.boot.diagnostics.FailureAnalyzer",
            "org.springframework.boot.autoconfigure.template.TemplateAvailabilityProvider",
            "org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration"
    );
}
