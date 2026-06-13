package io.jrdi.storage.repo;

import java.util.List;

public interface MybatisStatementRepo extends Repo {

    enum Kind { SELECT, INSERT, UPDATE, DELETE }

    record Record(long id, String namespace, String statementId, Kind kind,
                 String sqlTemplate, String sqlNormalized, List<String> parameters,
                 String definedInFile, Integer line,
                 String providerClass, String providerMethod) {}

    long upsert(String namespace, String statementId, Kind kind,
                String sqlTemplate, String sqlNormalized, List<String> parameters,
                String definedInFile, Integer line,
                String providerClass, String providerMethod);

    List<Record> findByNamespace(String namespace);

    List<Record> findByNamespaceAndId(String namespace, String statementId);
}
