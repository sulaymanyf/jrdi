package io.jrdi.resolver;

/**
 * Identifies a logical Maven repository the artifact came from. The default {@link #LOCAL}
 * covers {@code ~/.m2/repository}; custom remotes (configured in {@code settings.xml}) get
 * the URL of the {@code <repository>} or {@code <mirror>}.
 */
public record OriginRepo(String id, String url) {

    public static final OriginRepo LOCAL = new OriginRepo("local", "file://~/.m2/repository");

    public boolean isLocal() {
        return "local".equals(id);
    }
}
