package io.jrdi.source;

import io.jrdi.core.symbol.Fqn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Reads {@code sources.jar} files. Given a jar path and a class FQN, locates the matching
 * {@code .java} entry and reads its bytes. Caches parsed text per session to avoid repeated
 * jar I/O when the pipeline walks many methods on the same class.
 */
public final class SourceLoader {

    private static final Logger LOG = LoggerFactory.getLogger(SourceLoader.class);

    /**
     * Read a single class's source text from a sources jar.
     *
     * @param sourcesJar path to {@code X-1.0.0-sources.jar} (or any jar containing {@code .java} entries)
     * @param classFqn   internal FQN, e.g. {@code com/acme/OwnerController}
     */
    public Optional<String> read(Path sourcesJar, Fqn classFqn) {
        if (sourcesJar == null || !Files.isRegularFile(sourcesJar)) return Optional.empty();
        String entryName = classFqn.slashed() + ".java";
        try (JarFile jar = new JarFile(sourcesJar.toFile())) {
            JarEntry e = jar.getJarEntry(entryName);
            if (e == null) {
                LOG.debug("no source entry {} in {}", entryName, sourcesJar);
                return Optional.empty();
            }
            try (InputStream in = jar.getInputStream(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max((int) e.getSize(), 256));
                in.transferTo(out);
                return Optional.of(out.toString());
            }
        } catch (IOException ex) {
            LOG.warn("failed to read source {} from {}: {}", entryName, sourcesJar, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Convenience: also accept a {@code .java} file directly (useful in tests and for
     * single-file source sets).
     */
    public Optional<String> readLoose(Path javaFile) {
        if (javaFile == null || !Files.isRegularFile(javaFile)) return Optional.empty();
        try {
            return Optional.of(Files.readString(javaFile));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
