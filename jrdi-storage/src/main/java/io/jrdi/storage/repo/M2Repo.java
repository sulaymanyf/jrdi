/*
 * Copyright 2026 sulaymanyf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.jrdi.storage.repo;

import io.jrdi.core.symbol.Fqn;

import java.util.List;
import java.util.Optional;

/**
 * Lazy resolution of class facts from jars in the local Maven cache
 * (~/.m2/repository). The {@code m2_*} tables are populated on
 * demand when an MCP query hits a {@code dubbo_services} row with
 * {@code implClassId = 0} (a sentinel meaning "we didn't index the
 * implementing class") or when a {@code callers_of} query crosses
 * a class that lives in a jar outside the indexed project.
 *
 * <p>Unlike {@link ClassRepo}, this interface doesn't try to do
 * everything — it captures just enough facts (class metadata +
 * method signatures + invoke edges) for the LLM to chain a
 * cross-jar story. No source attribution, no framework pass, no
 * field details. That's deliberate: the m2_ tables are second-class
 * citizens, populated from cached state, evictable under memory
 * pressure, and explicitly separated from the "official" index.
 */
public interface M2Repo extends Repo {

    record CacheRow(long id, String jarPath, String jarSha256, long jarMtimeMs,
                    int classesCount, int methodsCount, int invokesCount,
                    String extractedAt, String lastAccessAt) {}

    record ClassRow(long id, Fqn fqn, Fqn superFqn, int access, String interfacesCsv,
                    String jarPath) {}

    record MethodRow(long id, long classId, String name, String desc, int access,
                     Integer line) {}

    record InvokeRow(long id, String callerClassFqn, long callerMethodId,
                     String calleeClassFqn, String calleeMethodName,
                     String calleeMethodDesc, String callKind) {}

    /**
     * Look up a cache row by absolute jar path. Used to gate
     * "do we need to re-extract?" decisions.
     */
    Optional<CacheRow> findCache(String jarPath);

    /**
     * Look up cache rows by content hash. Two jars with the same
     * content (e.g. one in the project, one in m2) share a cache
     * entry, so we don't pay the extraction cost twice.
     */
    List<CacheRow> findCacheBySha(String jarSha256);

    /**
     * Insert or update a cache row. Returns the row's id.
     */
    long upsertCache(String jarPath, String jarSha256, long jarMtimeMs,
                     int classesCount, int methodsCount, int invokesCount);

    /**
     * Update {@code last_access_at} for an LRU touch. Called after
     * every successful read against this jar.
     */
    void touchCache(long cacheId);

    /**
     * Forget a cache row and cascade-delete its m2_classes /
     * m2_methods / m2_invokes. Used both by mtime-invalidation and
     * by LRU eviction.
     */
    void evictCache(long cacheId);

    /**
     * Return the oldest {@code limit} cache rows by {@code last_access_at}
     * — used by the LRU eviction policy.
     */
    List<CacheRow> oldestCaches(int limit);

    // ─── Class / method / invoke writes ────────────────────────────

    long insertM2Class(Fqn fqn, Fqn superFqn, int access, String interfacesCsv,
                       String jarPath);

    Optional<ClassRow> findM2Class(Fqn fqn, String jarPath);

    List<ClassRow> findM2ClassesByFqn(Fqn fqn);

    long insertM2Method(long classId, String name, String desc, int access,
                        Integer line);

    void insertM2Invoke(String callerClassFqn, long callerMethodId,
                        String calleeClassFqn, String calleeMethodName,
                        String calleeMethodDesc, String callKind);

    // ─── Reads used by the lazy resolver callers ───────────────────

    List<MethodRow> methodsOf(long m2ClassId);

    /**
     * Find outgoing call edges from a class. Mirrors the
     * {@code invokes} query in {@code callgraph} but for the
     * m2-only subgraph.
     */
    List<InvokeRow> outgoingInvokes(String callerClassFqn);

    /**
     * Find incoming call edges to a class. Used by
     * {@code callers_of} when the callee lives in m2 only.
     */
    List<InvokeRow> incomingInvokes(String calleeClassFqn, String calleeMethodName);
}
