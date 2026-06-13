package io.jrdi.storage.repo;

public interface InvokeRepo extends Repo {

    enum Kind { VIRTUAL, STATIC, SPECIAL, INTERFACE, DYNAMIC, REFLECT, DUBBO, SPRING_INJECT, SQL_BIND }

    enum Confidence { CERTAIN, PROBABLE, UNCERTAIN }

    record Edge(long callerMethodId, String calleeOwner, String calleeName, String calleeDesc,
                Kind kind, Integer line, Confidence confidence) {}

    void insertAll(Iterable<Edge> edges);

    java.util.List<Edge> findCallersOf(String owner, String name, String desc);

    java.util.List<Edge> findCalleesOf(long callerMethodId);
}
