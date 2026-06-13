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
 */package io.jrdi.storage.repo;

public interface InvokeRepo extends Repo {

    enum Kind { VIRTUAL, STATIC, SPECIAL, INTERFACE, DYNAMIC, REFLECT, DUBBO, SPRING_INJECT, SQL_BIND }

    enum Confidence { CERTAIN, PROBABLE, UNCERTAIN }

    record Edge(long callerMethodId, String calleeOwner, String calleeName, String calleeDesc,
                Kind kind, Integer line, Confidence confidence) {}

    void insertAll(Iterable<Edge> edges);

    java.util.List<Edge> findCallersOf(String owner, String name, String desc);

    java.util.List<Edge> findCalleesOf(long callerMethodId);
}
