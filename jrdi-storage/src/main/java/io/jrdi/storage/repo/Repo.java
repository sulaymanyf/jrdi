package io.jrdi.storage.repo;

/**
 * Common parent for repository handle arguments. Each repository exposes a "find or create"
 * shape that the indexers use to avoid duplicate inserts while walking class lists.
 */
public interface Repo {

    long NONE = -1L;
}
