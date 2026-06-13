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
 */package io.jrdi.resolver;

import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Repository;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.apache.maven.settings.building.SettingsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Loads {@code ~/.m2/settings.xml} (overridable via {@code --maven-settings} or
 * env {@code JRDI_MVN_SETTINGS}) and exposes the parsed {@link Settings} object.
 *
 * <p>If the file is missing, returns a minimal {@code Settings} with a single
 * {@code central} remote at {@code https://repo.maven.apache.org/maven2} and a
 * local repo at {@code ~/.m2/repository}.
 */
public final class MavenSettingsParser {

    private static final Logger LOG = LoggerFactory.getLogger(MavenSettingsParser.class);
    public static final String ENV_KEY = "JRDI_MVN_SETTINGS";
    public static final String SYS_PROP = "jrdi.maven.settings";

    private MavenSettingsParser() {
    }

    public static Settings load() {
        return loadFromPath(customPath().orElse(defaultPath()));
    }

    public static Settings loadFromPath(Path path) {
        Settings defaults = defaults();
        if (path == null || !Files.isRegularFile(path)) {
            LOG.warn("settings.xml not found at {}; using built-in defaults", path);
            return defaults;
        }
        try {
            SettingsBuilder builder = new DefaultSettingsBuilderFactory().newInstance();
            SettingsSource src = new InputStreamSettingsSource(path);
            SettingsBuildingRequest req = new DefaultSettingsBuildingRequest();
            req.setUserSettingsSource(src);
            SettingsBuildingResult result = builder.build(req);
            Settings parsed = result.getEffectiveSettings();
            parsed.setLocalRepository(resolveLocalRepository(parsed.getLocalRepository()));
            LOG.info("loaded settings.xml from {}", path);
            return parsed;
        } catch (Exception e) {
            throw new ResolverException("read settings.xml from " + path, e);
        }
    }

    public static Optional<Path> customPath() {
        String fromEnv = System.getenv(ENV_KEY);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(Path.of(fromEnv));
        }
        String fromProp = System.getProperty(SYS_PROP);
        if (fromProp != null && !fromProp.isBlank()) {
            return Optional.of(Path.of(fromProp));
        }
        return Optional.empty();
    }

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
    }

    public static Settings defaults() {
        Settings s = new Settings();
        s.setLocalRepository(resolveLocalRepository(null));
        Mirror m = new Mirror();
        m.setId("central");
        m.setName("Maven Central");
        m.setUrl("https://repo.maven.apache.org/maven2");
        m.setMirrorOf("central");
        s.addMirror(m);
        Profile p = new Profile();
        p.setId("jrdi-default");
        Repository r = new Repository();
        r.setId("central");
        r.setUrl("https://repo.maven.apache.org/maven2");
        r.setLayout("default");
        p.addRepository(r);
        p.addPluginRepository(copy(r));
        s.addProfile(p);
        s.addActiveProfile("jrdi-default");
        return s;
    }

    private static Repository copy(Repository src) {
        Repository c = new Repository();
        c.setId(src.getId());
        c.setUrl(src.getUrl());
        c.setLayout(src.getLayout());
        return c;
    }

    private static String resolveLocalRepository(String fromSettings) {
        if (fromSettings != null && !fromSettings.isBlank()) {
            return fromSettings;
        }
        return new File(System.getProperty("user.home"), ".m2/repository").getAbsolutePath();
    }

    /**
     * Collect repositories from all profiles (active and non-active). For Maven,
     * profile-level repos are the canonical location.
     */
    public static List<Repository> collectAllRepositories(Settings settings) {
        List<Repository> out = new ArrayList<>();
        for (Profile p : settings.getProfiles()) {
            out.addAll(p.getRepositories());
            out.addAll(p.getPluginRepositories());
        }
        return out;
    }

    /** Adapter that exposes a {@link Path} as a {@link SettingsSource}. */
    private static final class InputStreamSettingsSource implements SettingsSource {
        private final Path path;

        InputStreamSettingsSource(Path path) {
            this.path = path;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new FileInputStream(path.toFile());
        }

        @Override
        public String getLocation() {
            return path.toString();
        }
    }
}
