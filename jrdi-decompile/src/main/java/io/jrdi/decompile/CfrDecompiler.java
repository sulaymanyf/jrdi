package io.jrdi.decompile;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Wraps CFR (Ben Fowler's decompiler) so the U4 pipeline can recover approximate source
 * line numbers when {@code sources.jar} is missing for an artifact.
 *
 * <p>The decompiled output is fed to {@link io.jrdi.source.AstBuilder} by the caller. We use
 * CFR's {@code STRING} sink to obtain the decompiled text; line numbers come from the natural
 * line position in that text (CFR's line-number sinks map back to <em>original</em> bytecode
 * lines, which is not what we want for a decompiler fallback).
 */
public final class CfrDecompiler {

    private static final Logger LOG = LoggerFactory.getLogger(CfrDecompiler.class);

    public Optional<String> decompileClass(Path jarPath, String internalName) {
        byte[] classBytes = readClassBytes(jarPath, internalName);
        if (classBytes == null) return Optional.empty();
        return Optional.ofNullable(decompileBytes(classBytes, internalName));
    }

    public String decompileBytes(byte[] classBytes, String internalName) {
        CollectingSink sink = new CollectingSink();
        Map<String, String> options = Map.of(
                "showversion", "false",
                "hideutf",     "true",
                "commentstyle", "none",
                "renamesimpleclassmembers", "false"
        );
        ClassFileSource source = new SingleClassSource(internalName, classBytes);
        CfrDriver driver = new CfrDriver.Builder()
                .withClassFileSource(source)
                .withOptions(options)
                .withOutputSink(sink)
                .build();
        try {
            driver.analyse(Collections.singletonList(internalName));
        } catch (Exception e) {
            LOG.warn("CFR decompile of {} failed: {}", internalName, e.getMessage());
            return null;
        }
        String out = sink.aggregate();
        if (out == null) return null;
        // CFR 0.152 prints a leading "Analysing type X" line into the STRING sink. Strip it
        // so downstream JavaParser can consume the result as plain Java.
        if (out.startsWith("Analysing type ")) {
            int nl = out.indexOf('\n');
            if (nl >= 0) out = out.substring(nl + 1);
        }
        if (out.trim().equals("null")) {
            LOG.debug("CFR produced no source for {}", internalName);
            return null;
        }
        return out;
    }

    private byte[] readClassBytes(Path jarPath, String internalName) {
        String entryName = internalName + ".class";
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry e = jar.getJarEntry(entryName);
            if (e == null) return null;
            try (InputStream in = jar.getInputStream(e)) {
                return in.readAllBytes();
            }
        } catch (IOException ex) {
            LOG.debug("cannot read {} from {}: {}", entryName, jarPath, ex.getMessage());
            return null;
        }
    }

    /** CFR {@link ClassFileSource} that serves a single class from in-memory bytes. */
    private static final class SingleClassSource implements ClassFileSource {
        private final String internalName;
        private final byte[] bytes;

        SingleClassSource(String internalName, byte[] bytes) {
            this.internalName = internalName;
            this.bytes = bytes;
        }

        @Override
        public void informAnalysisRelativePathDetail(String s, String s1) {
        }

        @Override
        public Collection<String> addJar(String s) {
            return List.of();
        }

        @Override
        public String getPossiblyRenamedPath(String s) {
            return s;
        }

        @Override
        public Pair<byte[], String> getClassFileContent(String path) {
            if (path.equals(internalName) || path.equals(internalName + ".class")) {
                return Pair.make(bytes, internalName);
            }
            return null;
        }
    }

    /** Plain String sink — collapses all string outputs into a single source text. */
    private static final class CollectingSink implements OutputSinkFactory {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public List<SinkClass> getSupportedSinks(SinkType sinkType, java.util.Collection<SinkClass> collection) {
            return List.of(SinkClass.STRING, SinkClass.DECOMPILED, SinkClass.LINE_NUMBER_MAPPING);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
            if (sinkClass == SinkClass.STRING) {
                return value -> buf.append(value).append('\n');
            }
            return value -> { };
        }

        String aggregate() {
            String s = buf.toString();
            return s.isEmpty() ? null : s;
        }
    }
}
