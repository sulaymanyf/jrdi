package io.jrdi.pipeline;

import java.time.Duration;
import java.util.Map;

/**
 * Summary of an indexing run, returned by the MCP {@code index_repo} tool and the CLI.
 *
 * <p>The fields are intentionally generic so the report works at the repo level (how many
 * artifacts changed) and at the class level (how many classes/methods/edges were added).
 *
 * <p>XML-discovered framework facts are reported separately from annotation-discovered
 * ones so the LLM can tell the provenance. The total counters ({@link #dubboServicesRecorded()},
 * {@link #dubboReferencesRecorded()}, {@link #mybatisStatementsRecorded()}) are sums of the
 * two sources.
 */
public record IndexReport(
        long repoId,
        Duration elapsed,
        int artifactsVisited,
        int classesIndexed,
        int classesSkipped,    // P3.6: incremental — class hash matched the DB, no work done
        int methodsIndexed,
        int fieldsIndexed,
        int invokesIndexed,
        int lambdasIndexed,
        int issuesRecorded,
        Map<String, Integer> perArtifactClassCount,
        // P2 framework analyzer counts (annotation-discovered, zero when no annotations present)
        int springBeansRecorded,
        int springInjectsRecorded,
        int dubboServicesFromAnnotation,
        int dubboReferencesFromAnnotation,
        int mybatisStatementsFromAnnotation,
        // P2 framework analyzer counts (XML-discovered)
        int dubboServicesFromXml,
        int dubboReferencesFromXml,
        int mybatisStatementsFromXml,
        int dubboMethodConfigsFromXml,
        int mybatisResultMapsFromXml,
        int springXmlBeansFromXml,
        // V5: Spring Boot auto-config (spring.factories + AutoConfiguration.imports)
        int springBootAutoconfigsFromXml,
        // V6: Spring Boot auto-config conditions
        int springAutoconfigConditionsFromXml,
        // V7: Dubbo registries
        int dubboRegistriesFromXml,
        int dubboXmlFilesScanned,
        int mybatisXmlFilesScanned,
        int springXmlFilesScanned
) {
    public int dubboServicesRecorded() {
        return dubboServicesFromAnnotation + dubboServicesFromXml;
    }
    public int dubboReferencesRecorded() {
        return dubboReferencesFromAnnotation + dubboReferencesFromXml;
    }
    public int mybatisStatementsRecorded() {
        return mybatisStatementsFromAnnotation + mybatisStatementsFromXml;
    }
    public int totalFacts() {
        return classesIndexed + methodsIndexed + fieldsIndexed + invokesIndexed + lambdasIndexed
                + springBeansRecorded + springInjectsRecorded
                + dubboServicesRecorded() + dubboReferencesRecorded()
                + mybatisStatementsRecorded()
                + dubboMethodConfigsFromXml + mybatisResultMapsFromXml
                + springXmlBeansFromXml + springBootAutoconfigsFromXml
                + springAutoconfigConditionsFromXml;
    }
}
