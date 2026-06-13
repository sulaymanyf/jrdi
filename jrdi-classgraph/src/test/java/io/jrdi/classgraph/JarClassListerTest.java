package io.jrdi.classgraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class JarClassListerTest {

    @Test
    void lists_class_files(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("fixture.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("com/acme/Foo.class"));
            z.write(new byte[]{1, 2, 3}, 0, 3);
            z.closeEntry();
            z.putNextEntry(new ZipEntry("com/acme/Bar.class"));
            z.write(new byte[]{1, 2, 3}, 0, 3);
            z.closeEntry();
            z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            z.write("Manifest-Version: 1.0\n".getBytes());
            z.closeEntry();
        }
        List<String> classes = new JarClassLister().list(jar);
        assertThat(classes).containsExactlyInAnyOrder("com/acme/Foo", "com/acme/Bar");
    }

    @Test
    void skips_module_info(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("mod.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("module-info.class"));
            z.write(new byte[]{1, 2, 3}, 0, 3);
            z.closeEntry();
            z.putNextEntry(new ZipEntry("com/acme/Baz.class"));
            z.write(new byte[]{1, 2, 3}, 0, 3);
            z.closeEntry();
        }
        List<String> classes = new JarClassLister().list(jar);
        assertThat(classes).containsExactly("com/acme/Baz");
    }
}
