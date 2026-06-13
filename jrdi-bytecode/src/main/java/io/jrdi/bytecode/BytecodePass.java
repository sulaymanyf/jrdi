package io.jrdi.bytecode;

import io.jrdi.core.symbol.Fqn;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Top-level entry: read a class (from a jar or loose .class) and run a fresh
 * {@link ClassVisitorEx} over its bytecode to produce a {@link PassResult}.
 *
 * <p>For U3 we process one class at a time. The U6 pipeline calls this in parallel per
 * {@link Fqn} discovered by {@link io.jrdi.classgraph.JarClassLister}.
 */
public final class BytecodePass {

    public PassResult run(Fqn classFqn, Path jarPath) {
        String entryName = classFqn.slashed() + ".class";
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) {
                throw new IllegalArgumentException("class not in jar: " + entryName + " in " + jarPath);
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return run(classFqn, in.readAllBytes());
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to read class " + classFqn + " from " + jarPath, e);
        }
    }

    public PassResult run(Fqn classFqn, byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassVisitorEx cv = new ClassVisitorEx();
        cr.accept(cv, ClassReader.SKIP_FRAMES);
        PassResult r = cv.build();
        if (r.fqn() == null) {
            throw new IllegalStateException("class fqn not visited");
        }
        if (!r.fqn().equals(classFqn)) {
            throw new IllegalStateException("expected " + classFqn + " but got " + r.fqn());
        }
        return r;
    }

    public PassResult runFromLooseClassFile(Path classFile) throws IOException {
        Fqn fqn = Fqn.fromDotted(classFile.getFileName().toString().replace(".class", ""));
        return run(fqn, Files.readAllBytes(classFile));
    }
}
