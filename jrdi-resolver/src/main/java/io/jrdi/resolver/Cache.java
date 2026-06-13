package io.jrdi.resolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * On-disk cache of jar/sources artifacts, keyed by sha256 of the jar bytes.
 * Layout:
 * <pre>
 *   {root}/jars/{sha256}.jar
 *   {root}/sources/{sha256}-sources.jar        (only when present)
 * </pre>
 */
public final class Cache {

    private static final Logger LOG = LoggerFactory.getLogger(Cache.class);

    private final Path root;

    public Cache(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path jarPath(String sha256) {
        return root.resolve("jars").resolve(sha256 + ".jar");
    }

    public Path sourcesPath(String sha256) {
        return root.resolve("sources").resolve(sha256 + "-sources.jar");
    }

    public boolean hasJar(String sha256) {
        return Files.isRegularFile(jarPath(sha256));
    }

    public boolean hasSources(String sha256) {
        return Files.isRegularFile(sourcesPath(sha256));
    }

    /**
     * Copy {@code source} into the cache under its sha256, returning the destination path.
     * If the destination already exists with the same content, the source is moved aside
     * (idempotent).
     */
    public Path installJar(Path source) {
        String sha = sha256(source);
        Path target = jarPath(sha);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                if (Files.size(target) == Files.size(source)) {
                    return target;
                }
                Files.delete(target);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw ResolverException.io("install jar", e);
        }
    }

    public Path installSources(Path source, String parentSha256) {
        Path target = sourcesPath(parentSha256);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw ResolverException.io("install sources", e);
        }
    }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] out = md.digest();
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new ResolverException("sha256 failed for " + file, e);
        }
    }
}
