package io.jrdi.classgraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathScannerTest {

    @Test
    void meta_inf_scanner_reads_services(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("meta.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("META-INF/services/com.example.MyService"));
            z.write("com.example.ImplA\n# comment\ncom.example.ImplB".getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("META-INF/spring.factories"));
            z.write("org.springframework.boot.autoconfigure.EnableAutoConfiguration=\\n  com.example.AutoConfigImpl\n".getBytes());
            z.closeEntry();
        }
        MetaInfScanner meta = new MetaInfScanner();
        List<String> services = meta.listServiceProviders(jar, "com.example.MyService");
        assertThat(services).containsExactly("com.example.ImplA", "com.example.ImplB");

        List<String> autoConfig = meta.listSpringFactories(jar,
                "org.springframework.boot.autoconfigure.EnableAutoConfiguration");
        assertThat(autoConfig).containsExactly("com.example.AutoConfigImpl");
    }

    @Test
    void meta_inf_missing_entries_return_empty(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("nometa.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            // empty
        }
        MetaInfScanner meta = new MetaInfScanner();
        assertThat(meta.listServiceProviders(jar, "nope")).isEmpty();
        assertThat(meta.listSpringFactories(jar, "nope")).isEmpty();
    }
}
