package io.jrdi.storage.repo;

public interface IssueRepo extends Repo {

    enum Severity { INFO, WARN, ERROR }

    record Record(long id, String kind, String target, String message, Severity severity, String detectedAt) {}

    void record(String kind, String target, String message, Severity severity, String detectedAt);

    java.util.List<Record> findByKind(String kind);
}
