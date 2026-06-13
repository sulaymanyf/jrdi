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
 */package io.jrdi.storage.repo.sqlite;

import io.jrdi.storage.Db;
import io.jrdi.storage.repo.ArtifactRepo;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.FieldRepo;
import io.jrdi.storage.repo.FileRepo;
import io.jrdi.storage.repo.InvokeRepo;
import io.jrdi.storage.repo.IssueRepo;
import io.jrdi.storage.repo.LambdaRepo;
import io.jrdi.storage.repo.MethodRepo;
import io.jrdi.storage.repo.RepoRepo;

/**
 * Convenience factory that wires SQLite repositories to a given {@link Db}.
 */
public final class SqliteRepos {

    private SqliteRepos() {
    }

    public static RepoRepo repoRepo(Db db) {
        return new SqliteRepoRepo(db.dataSource());
    }

    public static ArtifactRepo artifactRepo(Db db) {
        return new SqliteArtifactRepo(db.dataSource());
    }

    public static FileRepo fileRepo(Db db) {
        return new SqliteFileRepo(db.dataSource());
    }

    public static ClassRepo classRepo(Db db) {
        return new SqliteClassRepo(db.dataSource());
    }

    public static MethodRepo methodRepo(Db db) {
        return new SqliteMethodRepo(db.dataSource());
    }

    public static FieldRepo fieldRepo(Db db) {
        return new SqliteFieldRepo(db.dataSource());
    }

    public static InvokeRepo invokeRepo(Db db) {
        return new SqliteInvokeRepo(db.dataSource());
    }

    public static LambdaRepo lambdaRepo(Db db) {
        return new SqliteLambdaRepo(db.dataSource());
    }

    public static IssueRepo issueRepo(Db db) {
        return new SqliteIssueRepo(db.dataSource());
    }

    public static io.jrdi.storage.repo.SpringBeanRepo springBeanRepo(Db db) {
        return new SqliteSpringBeanRepo(db.dataSource());
    }

    public static io.jrdi.storage.repo.SpringInjectRepo springInjectRepo(Db db) {
        return new SqliteSpringInjectRepo(db.dataSource());
    }

    public static io.jrdi.storage.repo.DubboServiceRepo dubboServiceRepo(Db db) {
        return new SqliteDubboServiceRepo(db.dataSource());
    }

    public static io.jrdi.storage.repo.DubboReferenceRepo dubboReferenceRepo(Db db) {
        return new SqliteDubboReferenceRepo(db.dataSource());
    }

    public static io.jrdi.storage.repo.MybatisStatementRepo mybatisStatementRepo(Db db) {
        return new SqliteMybatisStatementRepo(db.dataSource());
    }

    public static io.jrdi.storage.repo.DubboMethodConfigRepo dubboMethodConfigRepo(Db db) {
        return new SqliteDubboMethodConfigRepo(db.dataSource());
    }

    public static io.jrdi.storage.repo.MybatisResultMapRepo mybatisResultMapRepo(Db db) {
        return new SqliteMybatisResultMapRepo(db.dataSource());
    }

    public static io.jrdi.storage.repo.SpringBootAutoconfigRepo springBootAutoconfigRepo(Db db) {
        return new SqliteSpringBootAutoconfigRepo(db.dataSource());
    }

    public static io.jrdi.storage.repo.SpringAutoconfigConditionRepo springAutoconfigConditionRepo(Db db) {
        return new SqliteSpringAutoconfigConditionRepo(db.dataSource());
    }

    public static io.jrdi.storage.repo.DubboRegistryRepo dubboRegistryRepo(Db db) {
        return new SqliteDubboRegistryRepo(db.dataSource());
    }
}
